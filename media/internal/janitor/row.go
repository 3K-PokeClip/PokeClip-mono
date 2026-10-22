package janitor

import (
	"context"
	"errors"
	"io/fs"
	"syscall"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/fsop"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// archiveAxis 는 지우는 파일의 축이다 — ② 합본 녹화(③ 은 로컬 파일이 없다). 삭제 로그의 axis 값이다(E7).
const archiveAxis = "archive"

// rejectStreamSymlink 는 스트림 디렉터리가 심링크라 거부한 사유다(janitor_path_rejected 의 reasons).
const rejectStreamSymlink = "stream_symlink"

// actResult 는 잠금 아래 파일 조작 한 번의 결과다.
type actResult struct {
	outcome string // removed · gone · unexpected_gone · rejected · stalled · failed
	clear   bool   // 장부를 NULL 해도 되는가
}

// deleteRow 는 새 행 하나를 처리한다(설계 3-2 「삭제」). 경로를 검증하고, 그 행의 잠금을 쥔 채
// 파일을 지우고, 결과에 따라 장부를 고친다.
func (j *Janitor) deleteRow(ctx context.Context, seg index.LocalSegment, st *cycleStats) {
	k := rowKey{seg.StreamID, seg.Seq}
	rel, reason := validatePath(j.opt.Root, seg)
	if reason != "" {
		st.reject(reason, seg)
		j.skip.put(k, skipRejected, j.now(), rejectedHold)
		return
	}
	var act actResult
	cctx, cancel := context.WithTimeout(ctx, j.limits.rowLock)
	defer cancel()
	res, err := j.ledger.RemoveUnderRowLock(cctx, seg.StreamID, seg.Seq, seg.LocalPath, func() bool {
		act = j.act(seg, rel, st)
		return act.clear
	})
	if err != nil {
		j.rowLockFailed(seg, res, act.outcome, err, st)
		return
	}
	switch res.Outcome {
	case index.RowLockCleared:
		st.cleared++
		j.skip.del(k)
		j.logDeleted(seg, act.outcome)
	case index.RowLockKept:
		// act 가 남긴 기록(보류 · 부채) 그대로다.
	case index.RowLockBusy:
		st.lockSkipped++
		j.skip.put(k, skipLocked, j.now(), j.opt.Interval)
	case index.RowLockGone:
		st.rowGone++
		j.log.Debug("janitor_row_gone", "stream_id", seg.StreamID, "seq", seg.Seq, "path", seg.LocalPath)
	case index.RowLockPathChanged:
		st.casLost++
		j.log.Warn("janitor_cas_lost", "stage", "pre", "stream_id", seg.StreamID, "seq", seg.Seq, "path", seg.LocalPath)
	}
}

// act 는 잠금 아래에서 파일을 지우고 그 결과로 「NULL 해도 되는가」를 정한다. ENOENT 는 나이가
// 지났을 때만(MediaMTX 가 먼저 지움 — K7) 인정한다.
func (j *Janitor) act(seg index.LocalSegment, rel string, st *cycleStats) actResult {
	k := rowKey{seg.StreamID, seg.Seq}
	st.fileOps++
	err := j.remove(rel)
	switch {
	case err == nil:
		st.removed++
		return actResult{outcome: "removed", clear: true}
	case errors.Is(err, fs.ErrNotExist) && seg.ExternalDeleteDue:
		st.gone++
		return actResult{outcome: "gone", clear: true}
	case errors.Is(err, fs.ErrNotExist):
		st.unexpectedGone++
		j.skip.put(k, skipUnexpected, j.now(), unexpectedHold)
		return actResult{outcome: "unexpected_gone"}
	case errors.Is(err, fsop.ErrSymlinkInPath):
		// 스트림 디렉터리가 심링크다 — 같은 루트의 다른 스트림을 가리키면 어휘 검증도 os.Root 도
		// 통과하므로 fsop 가 요소마다 거부한다(t2 r1 Q1). 우리 송출에서 recordings 에 심링크를 만드는
		// 주체는 없다(MediaMTX 는 디렉터리 · 파일만 만든다) — 생기면 사람이 볼 일이라 경로 거부와 같이 다룬다.
		st.reject(rejectStreamSymlink, seg)
		j.skip.put(k, skipRejected, j.now(), rejectedHold)
		return actResult{outcome: "rejected"}
	}
	if isStall(err) {
		// 버려진 워커가 뒤늦게 지울 수 있다 — 장부는 그대로 두고 부채(maybe)로 들고 회수에서 다시 본다.
		st.stalled++
		j.log.Error("fs_op_stalled", "op", "remove", "site", "janitor_remove", "path", seg.LocalPath)
		j.latch.Trip(seg.LocalPath, "janitor_remove")
		j.addDebt(seg, debtMaybe, "")
		j.enterFSHold()
		return actResult{outcome: "stalled"}
	}
	reason := failReason(err)
	st.failed[reason]++
	j.skip.putFailed(k, j.now())
	j.log.Debug("janitor_delete_failed", "stream_id", seg.StreamID, "seq", seg.Seq, "path", seg.LocalPath, "reason", reason, "err", err)
	return actResult{outcome: "failed"}
}

// failReason 은 삭제 실패를 사유 셋으로 가른다(summary failed_<사유>).
func failReason(err error) string {
	switch {
	case errors.Is(err, syscall.EACCES) || errors.Is(err, syscall.EPERM):
		return "permission"
	case errors.Is(err, syscall.EROFS):
		return "readonly"
	}
	return "other"
}

// rowLockFailed 는 RemoveUnderRowLock 오류를 3-3 표대로 다룬다. 파일을 지운 뒤(Acted · Cleared)
// COMMIT 을 확인하지 못했으면 부채(deleted)를 남긴다 — 삭제 결과(outcome)를 함께 들어 해결 때 줄에
// 싣는다. 어느 경우든 주기는 멈춘다.
func (j *Janitor) rowLockFailed(seg index.LocalSegment, res index.RowLockResult, outcome string, err error, st *cycleStats) {
	stage := stageName(res.Stage)
	if res.Stage == index.StageAcquire {
		st.abort("acquire", stage, err)
		return
	}
	if res.Stage == index.StageCommit && res.Acted && res.Cleared {
		j.addDebt(seg, debtDeleted, outcome)
	}
	st.abort(classifyRowLock(err), stage, err)
}

func stageName(s index.RowLockStage) string {
	switch s {
	case index.StageAcquire:
		return "acquire"
	case index.StageLock:
		return "lock"
	case index.StageCommit:
		return "commit"
	}
	return "unknown"
}

// logDeleted 는 행마다 삭제 한 줄을 INFO 로 낸다(E7 — 완료 조건 6 「건수 · 경로 · 축」). basis_age 는
// 관측용이다 — 판정은 DB 시계(now())가 했다. extra 는 뒤에 붙는 속성 쌍이다(회수의 row_probe 표지).
func (j *Janitor) logDeleted(seg index.LocalSegment, outcome string, extra ...any) {
	attrs := []any{"stream_id", seg.StreamID, "seq", seg.Seq, "path", seg.LocalPath, "axis", archiveAxis,
		"outcome", outcome, "grace_basis", seg.GraceBasis, "basis_age", j.now().Sub(seg.GraceBasis)}
	j.log.Info("segment_local_deleted", append(attrs, extra...)...)
}

// isStall 은 err 가 FS 상한 초과인가다.
func isStall(err error) bool { return errors.Is(err, fsop.ErrStalled) }
