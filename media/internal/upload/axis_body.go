package upload

import (
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"io"
	"log/slog"
	"os"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// 이 파일은 워커의 **③·init 축 본문**이다(계획 2.1 worker.go 행). ② 는 열린 녹화 파일을 그대로
// 올리고, ③·init 은 그 파일을 재포장한 메모리 산출을 올린다 — 도장 위치(스위퍼는 보정값 표)·
// PUT 전 init 대조·대조 보류(보류 목록)·init 확정이 여기 있다. 재시도·게이트·마킹 골격은 worker.go 다.

// ③ 실패 CAS 의 사유다. 세션을 끝내는 사유는 index.ReasonInitMismatch 하나뿐이고, 아래는
// 조각만 failed 로 적는 갈래의 이름표다(장부에 남지 않는다 — 오류 문맥용).
const (
	playbackReasonFileMissing   = "file_missing"
	playbackReasonUploadFailed  = "upload_failed"
	playbackReasonNoOffsetTable = "no_offset_table"
)

// withInitKey 는 키를 비워 온 init 대상에 세션 축 키를 채운다(설계 5.3ⓐ · 계획 2.1).
//
// init 키는 예약값이 아니라 파생값이다 — upload_store 의 "재계산하지 않는다" 계약의 명시적
// 예외이며, 스위퍼의 init 조회도 키를 싣지 않는다. 파생할 수 없는 성분이면 빈 키 그대로 두어
// [0] 이 bad_key 로 거른다 — 키 문법 검사(initKeyRe)가 대체 보증이다.
func withInitKey(t index.UploadTarget) index.UploadTarget {
	if t.Axis != index.AxisInit || t.S3Key != "" {
		return t
	}
	if key, err := playback.InitKey(t.StreamID, t.SessionID); err == nil {
		t.S3Key = key
	}
	return t
}

// payload 는 이번 시도에 올릴 본문이다. ② 는 열린 파일 그대로이고 ③·init 은 재포장 산출
// (메모리)이다 — ③ 산출은 디스크에 쓰지 않는다(G4).
type payload struct {
	body io.Reader
	size int64
	// initSHA 는 init 산출의 해시다 — 첫 init CAS 가 기록한다(다른 축에서는 비어 있다).
	initSHA []byte
}

// payloadFor 는 축이 고르는 본문이다. ok 가 false 면 이번 시도는 r 로 끝난다.
func (u *Uploader) payloadFor(j job, f *os.File, size int64, lg *slog.Logger) (p payload, r attemptResult, ok bool) {
	switch j.target.Axis {
	case index.AxisPlayback:
		return u.playbackPayload(j, f, lg)
	case index.AxisInit:
		out, r, ok := u.produce(j, f, 0, lg) // init 바이트는 도장 위치와 무관하다
		if !ok {
			return payload{}, r, false
		}
		return payload{body: bytes.NewReader(out.Init), size: int64(len(out.Init)), initSHA: out.InitSHA256[:]}, attemptResult{}, true
	default:
		return payload{body: f, size: size}, attemptResult{}, true
	}
}

// playbackPayload 는 ③ 조각 본문이다 — 도장 위치를 정해 재포장하고, 산출 init 이 세션의
// MAP 과 같을 때만 올린다(설계 5.3ⓑ·ⓒ — **PUT 전 대조**).
//
// 순서가 계약이다: 위치(스위퍼는 표) → 기대 init(없으면 보류) → 재포장 → 대조. 표 판정이 보류
// 판정보다 앞이라 보류 목록에는 위치를 확정한 작업만 든다(계획 4.2-R R3 「스위퍼 재생성」).
func (u *Uploader) playbackPayload(j job, f *os.File, lg *slog.Logger) (payload, attemptResult, bool) {
	t := j.target
	pos, r, ok := u.playbackPos(j, f, lg)
	if !ok {
		return payload{}, r, false
	}
	t.PlaybackPos = pos
	want, known := u.expectedInit(t)
	if !known {
		return payload{}, u.holdForInit(t, lg), false
	}
	out, r, ok := u.produce(j, f, pos, lg)
	if !ok {
		return payload{}, r, false
	}
	if !bytes.Equal(want, out.InitSHA256[:]) {
		return payload{}, u.failInitMismatch(j, want, out.InitSHA256[:], lg), false
	}
	return payload{body: bytes.NewReader(out.Seg), size: int64(len(out.Seg))}, attemptResult{}, true
}

// playbackPos 는 이 ③ 조각의 도장 위치다(계획 4.2-R R3). 실시간 작업(보류 재요청 포함)은 실려
// 온 위치를 그대로 쓴다. 스위퍼 작업은 장부에 위치가 없어 회차의 보정값 표로 다시 만든다 —
// 실시간과 **같은 값**이다: 고정 줄이 있으면 그 위치(판독 생략), 없으면 이미 연 입력에서 mtxi 를
// 읽어(위치는 되돌려 놓는다 — 재포장이 같은 fd 를 처음부터 읽는다) offset 줄을 더한다.
func (u *Uploader) playbackPos(j job, f *os.File, lg *slog.Logger) (time.Duration, attemptResult, bool) {
	t := j.target
	if j.origin != OriginSweep {
		return t.PlaybackPos, attemptResult{}, true
	}
	if pos, ok := u.sessions.pinnedPos(t.SessionID, t.Seq); ok {
		return pos, attemptResult{}, true
	}
	offset, ok := u.sessions.offsetAt(t.SessionID, t.Seq)
	if !ok {
		return 0, u.refuseWithoutTable(j, lg), false
	}
	m, err := playback.ReadMtxi(f)
	if err != nil {
		return 0, attemptResult{kind: attemptRetryable, err: err}, false
	}
	return m.DTS + offset, attemptResult{}, true
}

// refuseWithoutTable 은 스위퍼 ③ 작업의 위치를 표로 정할 수 없을 때다 — 도장을 합성하지 않는다
// (결정 B′: 재기동 복구는 범위 밖 · 계획 4.2-R R3 「알려진 한계」). 그 조각은 failed(GAP)가 되고
// 재수집 대상으로 남는다(계약 5-5). 보류하지 않는다 — 보류 목록에는 위치를 확정한 작업만 든다.
func (u *Uploader) refuseWithoutTable(j job, lg *slog.Logger) attemptResult {
	t := j.target
	if !u.markFailedByAxis(j, playbackReasonNoOffsetTable, lg) {
		return attemptResult{outcome: outcomeNeutral}
	}
	k := j.key()
	u.gate.registerFailure(k)
	nextAt, _ := u.gate.backoffBlocked(k)
	lg.Error("upload_failed", "s3_key", t.S3Key, "reason", playbackReasonNoOffsetTable,
		"session_id", t.SessionID, "next_attempt_at", nextAt.UTC().Format(time.RFC3339))
	return attemptResult{outcome: outcomeNeutral}
}

// expectedInit 은 이 ③ 조각이 기대하는 세션 MAP 의 해시다(계획 2.1). 스위퍼 작업은 조회가 실어
// 온 세션 확정값(ExpectedInitSHA)을 쓰고, 실시간 작업은 그 필드를 **늘 비워 오므로** 이 워커가
// init CAS 로 확정한 sessionInit 을 쓴다. 둘 다 없을 때만 아직 모르는 것이다 — 대조 보류.
func (u *Uploader) expectedInit(t index.UploadTarget) ([]byte, bool) {
	if len(t.ExpectedInitSHA) > 0 {
		return t.ExpectedInitSHA, true
	}
	return u.sessions.initOf(t.SessionID)
}

// holdForInit 은 기대 init 을 아직 모르는 ③ 작업을 보류 목록에 든다 — 올리지도 실패로 적지도
// 않는다(장부 pending 유지). 백오프도 걸지 않는다: init 이 확정되면 그 자리에서 다시 넣는다.
// 상한에 닿으면 들지 않는다 — 그 조각은 스위퍼가 다시 집는다(계획 2.1).
func (u *Uploader) holdForInit(t index.UploadTarget, lg *slog.Logger) attemptResult {
	if !u.sessions.hold(t, u.now()) {
		u.logHeldListFull(t, lg)
		return attemptResult{outcome: outcomeNeutral}
	}
	lg.Debug("upload_mark_skipped", "reason", "init_pending", "session_id", t.SessionID)
	return attemptResult{outcome: outcomeNeutral}
}

// logHeldListFull 은 보류 목록 넘침을 남긴다. 세션당 첫 번만 WARN 이다 — init 이 끝내 확정되지 않는
// 회차는 조각마다(4초) 넘치므로 매번 WARN 이면 그 방송이 끝날 때까지 경보가 쏟아진다. 그 뒤는 Debug 다.
func (u *Uploader) logHeldListFull(t index.UploadTarget, lg *slog.Logger) {
	args := []any{"reason", "held_list_full", "session_id", t.SessionID, "limit", u.opt.HeldPerSession}
	if u.sessions.firstOverflow(t.SessionID) {
		lg.Warn("upload_mark_skipped", args...)
		return
	}
	lg.Debug("upload_mark_skipped", args...)
}

// failInitMismatch 는 산출 init 이 세션의 MAP 과 다를 때다(설계 5.3ⓒ — fail-closed). 조각은
// 올리지 않고, 조각 failed 와 세션 ending(init_mismatch)을 한 문장으로 영속한다(MarkPlaybackFailed
// 의 결속 갈래 — 크래시에도 남는다). 다음 유입이 새 세션·새 init 을 연다.
func (u *Uploader) failInitMismatch(j job, want, got []byte, lg *slog.Logger) attemptResult {
	t := j.target
	lg.Error("session_init_mismatch", "session_id", t.SessionID,
		"want_sha256", hex.EncodeToString(want), "got_sha256", hex.EncodeToString(got))
	if u.markFailedByAxis(j, index.ReasonInitMismatch, lg) {
		u.dirty.markSplit(t.StreamID, t.SessionID, index.ReasonInitMismatch)
		u.gate.registerFailure(j.key()) // 재수집마다 같은 ERROR 를 되풀이하지 않게 벌린다
	}
	return attemptResult{outcome: outcomeNeutral}
}

// requeueHeld 는 보류에서 꺼낸 ③ 작업을 실시간 경로로 다시 넣는다 — 확정한 도장 위치를 실은
// 그대로다. 인덱서의 기록 경로(RequestUpload)는 지나지 않는다(계획 4.2-R R3 규칙 ⑤). 들지 못한
// 작업(게이트 거부·큐 포화)은 목록에 되돌려 다음 워커 완료·tick 에 다시 넣는다.
//
// 큐 포화에서는 멈추고 그 작업과 남은 작업을 전부 되돌린다(Phase 3 r4 cc #1). 게이트 거부는 그 키의
// 사정이라 뒤 작업을 계속 넣지만, 포화는 뒤 작업에도 똑같이 걸린다 — 계속 넣어 보면 큐가 찬 동안
// drain 마다(init 확정·작업 완료·tick) 작업 수만큼 경보가 쏟아진다. 포화는 Debug 요약 한 줄이다 —
// 그 줄의 stream_id·seq 는 포화를 만나 멈춘 작업이고, held_returned 는 이 drain 이 되돌리는 총수(앞서
// 거부된 몫 포함, 스트림·회차 불문 — 그래서 회차(session_id)는 싣지 않는다)다. 그사이 확정된 조각은
// giveBack 이 거르므로 실제 목록 수는 이보다 작을 수 있다(Phase 3 r5 cx #1).
func (u *Uploader) requeueHeld(targets []index.UploadTarget) {
	var left []index.UploadTarget
	for i, t := range targets {
		out := u.enqueueAt(t, OriginLive, slog.LevelDebug,
			"held_returned", len(left)+len(targets)-i)
		if out == EnqueueQueueFull {
			left = append(left, targets[i:]...)
			break
		}
		if out != EnqueueAdmitted {
			left = append(left, t)
		}
	}
	if len(left) > 0 {
		u.sessions.giveBack(left)
	}
}

// drainHeld 는 init 이 확정된 세션에 남아 있던 보류 작업을 다시 넣는다.
func (u *Uploader) drainHeld() {
	u.requeueHeld(u.sessions.claimReady())
}

// produce 는 열린 입력을 재포장한다. 산출이 없으면 올릴 것이 없다(계획 2.1 · A6). 실패는 두 갈래다:
// 같은 입력이면 늘 같은 실패(sameInputFailsAgain)는 첫 시도에서 끝내고, 나머지(입력 해석 실패·임의
// 오류)는 PUT 실패와 같은 재시도 사다리를 탄다.
func (u *Uploader) produce(j job, f *os.File, pos time.Duration, lg *slog.Logger) (playback.Output, attemptResult, bool) {
	out, err := u.opt.Producer.Produce(u.putCtx, playback.Request{Input: f, PlaybackPos: pos})
	if err != nil {
		if u.putCtx.Err() != nil {
			lg.Debug("upload_aborted_shutdown")
			return playback.Output{}, attemptResult{outcome: outcomeShutdown}, false
		}
		if sameInputFailsAgain(err) {
			return playback.Output{}, u.failUnproducible(j, err, lg), false
		}
		return playback.Output{}, attemptResult{kind: attemptRetryable, err: err}, false
	}
	return out, attemptResult{}, true
}

// sameInputFailsAgain 은 다시 만들어도 같은 이유로 실패할 산출 오류인가다 — 무결성 ⓑ 트랙 부족 · ⓒ
// 빈 트랙 · ⓓ 크기 상한(playback 오류값). 워커가 재포장하는 입력은 닫혔거나 장부 크기 그대로라
// 다시 읽어도 같다. ⓐ 입력 해석 실패는 넣지 않는다 — 쓰는 중에 잘린 입력일 수 있어 다시 읽으면 달라진다.
func sameInputFailsAgain(err error) bool {
	return errors.Is(err, playback.ErrMissingTracks) || errors.Is(err, playback.ErrEmptyTrack) ||
		errors.Is(err, playback.ErrInputTooLarge)
}

// failUnproducible 은 같은 입력이면 늘 같은 산출 실패다(cc r3 #1). 사다리 없이 첫 시도에서 끝낸다 —
// ③ 은 failed 로 확정하고(재수집 대상 — 계약 5-5) init 은 장부 없이(실패 CAS 가 없다 — 설계 5.5.5)
// 키 백오프를 건다. 사다리(1+2+4초)를 태우면 하나뿐인 워커가 조각마다 7초씩 묶여 모든 스트림의 ②·③ 이
// 큐에서 기다린다. S3 에 닿지 않았으니 브레이커 판정 재료가 아니다(refuseWithoutTable 과 같다).
func (u *Uploader) failUnproducible(j job, err error, lg *slog.Logger) attemptResult {
	t := j.target
	if !u.markFailedByAxis(j, playbackReasonUploadFailed, lg) {
		return attemptResult{outcome: outcomeNeutral}
	}
	k := j.key()
	u.gate.registerFailure(k)
	nextAt, _ := u.gate.backoffBlocked(k)
	lg.Error("upload_failed", "s3_key", t.S3Key, "session_id", t.SessionID, "err", err.Error(),
		"next_attempt_at", nextAt.UTC().Format(time.RFC3339))
	return attemptResult{outcome: outcomeNeutral}
}

// afterUploaded 는 장부가 uploaded 로 바뀐 뒤의 통지다. ② 는 결과 채널(세그먼트 커서),
// ③·init 은 이벤트 집합이다(계획 2.1 — Result 는 ② 전용).
func (u *Uploader) afterUploaded(j job, p payload) {
	t := j.target
	switch t.Axis {
	case index.AxisPlayback:
		u.dirty.markUploaded(t.StreamID, t.Axis, t.Seq, t.SessionID)
		u.sessions.dropHeld(t.SessionID, t.Seq) // 같은 조각의 보류 사본은 이제 올릴 것이 없다(cc r4 #4)
	case index.AxisInit:
		u.dirty.markUploaded(t.StreamID, t.Axis, t.Seq, t.SessionID)
		// 세션 init 이 확정됐다 — 기대값을 적고 기다리던 ③ 을 그 자리에서 다시 넣는다(계획 2.1).
		u.requeueHeld(u.sessions.confirmInit(t.SessionID, p.initSHA, u.now()))
	default:
		u.sendResult(j, index.UploadStateUploaded)
	}
}

// markInitUploaded 는 세션 init 의 첫 업로드 CAS 다(설계 5.5.5 · 5.3ⓑ). 결과 4분기 중 이어가는
// 것은 이번에 확정했거나(Success) 이미 같은 바이트로 확정된(AlreadySame — 멱등 재시도) 둘이다.
//
// 계승 비호환 인자는 거짓으로 고정한다 — 계승 후보 개시가 아직 없어(SeedResult.PrevFirstLocalPath
// 언제나 빈 값) 게이트 비적용이다(계획 2.1 「ⓐ 단독 국면 $5=false」).
func (u *Uploader) markInitUploaded(ctx context.Context, j job, p payload, lg *slog.Logger) bool {
	t := j.target
	mark, err := u.st.MarkInitUploaded(ctx, t.SessionID, p.initSHA, t.S3Key, p.size, false)
	switch {
	case err != nil:
		lg.Error("mark_error", "target_state", "uploaded", "err", err.Error())
	case mark == index.InitMarkSuccess || mark == index.InitMarkAlreadySame:
		return true
	case mark == index.InitMarkMismatch:
		// 그 세션의 MAP 은 이미 다른 바이트로 굳었다 — 바꿀 수 없으므로 작업을 끝낸다. 분리하지
		// 않는다: init 작업에는 조각이 없다(계획 2.1 · verify-cx-3-r6 (ㄴ)).
		//
		// 이 분기는 S3 객체 보호가 아니다 — PUT 이 이 CAS 보다 먼저라, 본문이 달랐다면 그 키의 객체는
		// 이미 덮였고 장부 해시와 어긋난다. 같은 송출 설정·같은 재포장 산출(골든)이면 오지 않는다: 재포장 init 은 회차 안 어느
		// 조각에서 만들어도 같은 바이트다. 객체를 지키는 조건부 PUT 은 계획 부기의 ⓒ 후보다(cx r3 #2).
		lg.Error("session_init_mismatch", "session_id", t.SessionID,
			"init_mark", mark.String(), "got_sha256", hex.EncodeToString(p.initSHA))
	default:
		lg.Error("upload_cas_rejected", "target_state", "uploaded", "session_id", t.SessionID,
			"init_mark", mark.String())
	}
	u.gate.registerFailure(j.key())
	return false
}
