package janitor

import (
	"context"
	"errors"
	"io/fs"
	"path/filepath"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
)

// 회수 막힘 경보 문턱(연속 실패 수 — 설계 3-2). r3 의 드롭 문턱 값을 경보 문턱으로 옮겼다:
// maybe 3 = FS 보류가 상한에 닿기 전에 한 파일이 세 번 연속 멈춤, deleted 10 = 30초 주기로 약
// 5분 동안 같은 행이 계속 잠김. 문턱에 닿아도 부채를 빼지 않는다 — 경보만 낸다.
const (
	maybeBlockedFailures   = 3
	deletedBlockedFailures = 10
)

// debtKind 는 부채의 종류다.
type debtKind uint8

const (
	debtDeleted debtKind = iota + 1 // 지웠는데 COMMIT 을 확인하지 못했다
	debtMaybe                       // 삭제가 멈췄다 — 버려진 워커가 뒤늦게 지웠을 수 있다
)

func (k debtKind) String() string {
	if k == debtMaybe {
		return "maybe"
	}
	return "deleted"
}

func (k debtKind) blockedAt() int {
	if k == debtMaybe {
		return maybeBlockedFailures
	}
	return deletedBlockedFailures
}

// debtEntry 는 장부 갱신이 확인되지 않은 행 하나다(미회수 부채).
type debtEntry struct {
	seg  index.LocalSegment
	kind debtKind
	// outcome 은 확인된 삭제 결과다(removed · gone) — 해결 때 삭제 줄에 싣는다. maybe 는 회수 remove 가
	// 지웠거나 없음을 본 뒤에 채운다.
	outcome string
	// failures 는 지금 kind 의 연속 회수 실패 수다 — kind 가 바뀌면 0 부터 센다(문턱이 kind 마다 다르다).
	failures int
	// blockedKind 는 reclaim_blocked 를 켤 때의 kind 다(0 = 켜지 않음). clear 줄이 set 줄과 같은 kind 를
	// 싣게 한다.
	blockedKind debtKind
}

// addDebt 는 부채를 하나 더한다. 게이팅 때문에 정상 경로에서 부채는 최대 1 이다 — 둘이 되면
// 불변식이 깨진 것이니 ERROR 를 내고 이 실행 동안 새 삭제를 멈춘다(방어 단언). 부채는 그대로 회수한다.
func (j *Janitor) addDebt(seg index.LocalSegment, kind debtKind, outcome string) {
	j.debt = append(j.debt, debtEntry{seg: seg, kind: kind, outcome: outcome})
	if len(j.debt) > 1 {
		j.halted = true
		j.log.Error("janitor_invariant_violated", "debt_size", len(j.debt),
			"stream_id", seg.StreamID, "seq", seg.Seq, "path", seg.LocalPath,
			"note", "미회수 부채가 1을 넘었다 — 이 실행 동안 새 삭제를 멈춘다")
	}
}

// reclaim 은 맨 앞 부채 하나를 처리한다(설계 3-2 「회수」 — 드롭 없음). 부채는 장부 갱신을 확인하거나
// (ClearLocalPath true) 행이 그 경로를 더는 갖지 않을 때(false — COMMIT 이 실제로 됐거나 행이
// 사라짐)만 빠진다. maybe 는 회수 remove 가 nil · ENOENT 일 때만 deleted 로 바뀐다 — 일반 FS 오류는
// 파일이 있다는 증명이 아니다(늦은 unlink 를 배제하지 못한다 — X2).
func (j *Janitor) reclaim(ctx context.Context, st *cycleStats) {
	d := &j.debt[0]
	if d.kind == debtMaybe && !j.reclaimRemove(d, st) {
		return
	}
	cctx, cancel := context.WithTimeout(ctx, j.limits.reclaim)
	defer cancel()
	cleared, err := j.ledger.ClearLocalPath(cctx, d.seg.StreamID, d.seg.Seq, d.seg.LocalPath)
	switch {
	case err == nil:
		j.resolveDebt(ctx, cleared, st)
	case index.IsLockTimeout(err):
		j.debtFailed(d, st, "lock_timeout")
	default:
		st.recovery = "error"
		st.abort(classifyReclaim(err), "reclaim", err)
	}
}

// reclaimRemove 는 maybe 부채의 파일을 다시 지운다. 지웠거나 없으면 deleted 로 바꾸고 참이다. 없음도
// removed 로 기록한다 — 멈췄던 우리 워커가 뒤늦게 지웠을 가능성이 가장 크다.
func (j *Janitor) reclaimRemove(d *debtEntry, st *cycleStats) bool {
	st.fileOps++
	err := j.remove(relOf(j.opt.Root, d.seg))
	switch {
	case err == nil || errors.Is(err, fs.ErrNotExist):
		d.kind, d.outcome, d.failures = debtDeleted, "removed", 0
		return true
	case isStall(err):
		st.stalled++
		j.log.Error("fs_op_stalled", "op", "remove", "site", "janitor_reclaim", "path", d.seg.LocalPath)
		j.latch.Trip(d.seg.LocalPath, "janitor_reclaim")
		j.enterFSHold()
		j.debtFailed(d, st, "stalled")
	default:
		j.log.Debug("janitor_reclaim_failed", "stream_id", d.seg.StreamID, "seq", d.seg.Seq, "path", d.seg.LocalPath, "err", err)
		j.debtFailed(d, st, failReason(err))
	}
	return false
}

// debtFailed 는 회수 실패 하나를 센다 — 부채는 그대로다. 문턱에 닿으면 reclaim_blocked 를 켠다.
func (j *Janitor) debtFailed(d *debtEntry, st *cycleStats, why string) {
	d.failures++
	st.recovery = "blocked_" + why
	if d.failures < d.kind.blockedAt() {
		return
	}
	if d.blockedKind == 0 {
		d.blockedKind = d.kind
	}
	j.gauges.reclaimBlocked.set("kind", d.blockedKind.String(), "stream_id", d.seg.StreamID, "seq", d.seg.Seq,
		"path", d.seg.LocalPath, "failures", d.failures)
}

// resolveDebt 는 맨 앞 부채를 해결로 뺀다. 확인된 삭제 사실은 한 줄 낸다(E7):
//   - cleared — 이 회수가 장부를 NULL 했다.
//   - false 이고 행이 실재 — 앞선 COMMIT 이 서버에서는 됐는데 응답만 잃었다(경로가 이미 비었거나 다른
//     값). 파일 삭제는 확인됐으므로 줄을 낸다(t2 r1 Q3).
//   - false 이고 행 부재(clip DELETE) — 장부의 NULL 증가가 없으므로 줄을 내지 않는다(S-A 계수 대조).
//
// cleared 계수도 줄과 함께 올린다 — 응답을 잃은 주기는 cleared 를 세지 않았다.
func (j *Janitor) resolveDebt(ctx context.Context, cleared bool, st *cycleStats) {
	d := j.debt[0]
	j.debt = j.debt[1:]
	st.recovery = "resolved"
	if cleared || j.rowRemains(ctx, d.seg) {
		st.cleared++
		j.logDeleted(d.seg, d.outcome)
	}
	kind := d.kind
	if d.blockedKind != 0 {
		kind = d.blockedKind
	}
	j.gauges.reclaimBlocked.clear("kind", kind.String(), "stream_id", d.seg.StreamID, "seq", d.seg.Seq)
}

// rowRemains 는 회수 CAS 가 false 로 끝난 행이 장부에 아직 있는가다. 장부에 따로 묻는 문장이 없어
// RemoveUnderRowLock 을 아무것도 지우지 않는 콜백으로 부른다 — 그 행이 그 경로를 갖지 않으면 잠금
// 뒤 콜백 없이 PathChanged(행 실재) · Gone(행 부재)으로 끝난다. 그 밖(Busy · 오류 · 경로가 다시 같음)은
// 모름이라 거짓이다 — 줄을 내지 않는 쪽이다.
func (j *Janitor) rowRemains(ctx context.Context, seg index.LocalSegment) bool {
	cctx, cancel := context.WithTimeout(ctx, j.limits.reclaim)
	defer cancel()
	res, err := j.ledger.RemoveUnderRowLock(cctx, seg.StreamID, seg.Seq, seg.LocalPath, func() bool { return false })
	if err != nil || (res.Outcome != index.RowLockPathChanged && res.Outcome != index.RowLockGone) {
		j.log.Debug("janitor_reclaim_probe_unknown", "stream_id", seg.StreamID, "seq", seg.Seq, "path", seg.LocalPath,
			"outcome", int(res.Outcome), "err", err)
		return false
	}
	return res.Outcome == index.RowLockPathChanged
}

// relOf 는 부채 행의 루트 기준 경로다. 부채는 삭제 단계에서 검증을 통과한 행만 들어온다.
func relOf(root string, seg index.LocalSegment) string {
	rel, _ := filepath.Rel(filepath.Clean(root), filepath.Clean(seg.LocalPath))
	return rel
}
