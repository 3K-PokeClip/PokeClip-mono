package upload

// 이 파일은 ③ 사슬 복원(E18 — POK-196 PDT 계획 4절 c10b · 설계 r6 2.10)의 바깥이다 — 언제 · 어디서 도는가와 인덱서에 돌려줄
// 링크. 구멍 하나를 되살리는 산식은 restore_group.go, 행의 원천(② 로컬 · ② 객체 · ③ 객체) 읽기는 restore_source.go 다.
//
// 인덱서 루프는 S3 를 부르지 않는다(r6 「복원하는 곳 — 업로더 하나」). 인덱서는 사슬을 모르는 행을 위치 모름(PosUnknown)으로
// 요청하고, 업로더가 그 요청 · 스위퍼의 표 없는 ③ 작업에서 깨어 장부와 S3 로 사슬을 되살린다. 결과는 표 줄(스위퍼 재생성 ·
// 다시 넣은 작업)과 복원 링크(인덱서가 RestoredLink 로 읽는다)다. 복원은 고루틴 하나에서만 돈다 — 세션당 단일 실행이고
// 업로더 전체 동시 1 이다. 실패는 행 상태를 바꾸지 않고 세션 단위 백오프로 다시 본다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
)

// ChainLedger 는 복원이 읽는 장부 조회 둘이다(index.LoadPlaybackHoles · LoadPlaybackChainRows). UploadStore 에 얹지 않는다 —
// UploadStore 의 가짜 구현들이 안 쓰는 메서드를 떠안는다(그 인터페이스의 결정 2 와 같은 이유). 업로더는 받은 장부가 이것도
// 구현하고 Putter 가 Getter 도 구현할 때만 복원한다 — 아니면 표 없는 스위퍼 ③ 작업은 지금처럼 failed(GAP)다.
type ChainLedger interface {
	PlaybackHoles(ctx context.Context, sessionID string) ([]index.PlaybackHole, error)
	PlaybackChainRows(ctx context.Context, streamID string, from, to int64) ([]index.PlaybackChainRow, error)
}

// restoreLookahead 는 마지막 구멍 뒤로 더 읽는 장부 행 수다 — 구멍 뒤 uploaded 행 대조와 꼬리 판정의 재료다(약 2분). 그 안에
// 같은 회차의 uploaded 행이 없으면 대조하지 않는다.
const restoreLookahead = 32

// restoreDeps 는 복원의 두 재료다 — ok 가 거짓이면 이 업로더는 복원하지 않는다.
func (u *Uploader) restoreDeps() (ChainLedger, Getter, bool) {
	if u.off {
		return nil, nil, false
	}
	l, okL := u.st.(ChainLedger)
	g, okG := u.put.(Getter)
	return l, g, okL && okG
}

// RestoredLink 는 그 회차의 복원이 마지막으로 정한 링크다(설계 r6 2.10 「인덱서 쪽」). 인덱서 루프가 행마다 부른다 — 뮤텍스 아래
// 맵 읽기 하나라 막히지 않는다(RequestUpload 와 같은 비차단 계약). 링크의 seq 가 인덱서가 모르는 마지막 행이면 인덱서는 그
// 링크를 사슬로 받는다.
func (u *Uploader) RestoredLink(sessionID string) (playback.Link, bool) {
	if u.off || u.sessions == nil {
		return playback.Link{}, false
	}
	rl, ok := u.sessions.restoredLink(sessionID)
	return rl.link, ok
}

// startRestore 는 복원 고루틴을 띄운다 — 워커가 부르고, 돌려준 함수로 끝을 기다린다(워커가 끝나기 전에 — Shutdown 의 워커 join 이
// 복원도 덮는다). 복원하지 않는 업로더면 아무것도 띄우지 않는다.
func (u *Uploader) startRestore() (join func()) {
	if _, _, ok := u.restoreDeps(); !ok {
		return func() {}
	}
	done := make(chan struct{})
	go u.restoreLoop(done)
	return func() { <-done }
}

// restoreLoop 는 깨움을 기다려 회차마다 복원한다. 멈춤은 워커와 같은 workerStop 이다 — 도는 중인 S3 · DB 호출은 putCtx ·
// markRoot 가 Shutdown 5 · 6단계에서 끊는다(시한도 getTimeout · MarkTimeout).
func (u *Uploader) restoreLoop(done chan<- struct{}) {
	defer close(done)
	for {
		select {
		case <-u.workerStop:
			return
		case <-u.sessions.wakeCh():
		}
		for _, w := range u.sessions.takeWakes(u.now()) {
			select {
			case <-u.workerStop:
				return
			default:
			}
			u.restoreSession(w)
		}
	}
}

// wakeRestore 는 그 회차의 복원을 깨운다 — 복원하지 않는 업로더면 거짓이다.
func (u *Uploader) wakeRestore(streamID, sessionID string) bool {
	if _, _, ok := u.restoreDeps(); !ok {
		return false
	}
	u.sessions.wake(streamID, sessionID)
	return true
}

// errRestoreStopped 는 업로더가 멈추는 중이라 복원을 그만뒀다는 뜻이다.
var errRestoreStopped = errors.New("업로더 종료 중 — 복원을 멈췄다")

// restoreStopping 은 워커 정지 신호(Shutdown 4단계)가 왔는가다 — 막히지 않는다.
func (u *Uploader) restoreStopping() bool {
	select {
	case <-u.workerStop:
		return true
	default:
		return false
	}
}

// restoreError 는 복원 실패의 까닭(chain_restore_failed 의 reason)과 원인이다.
type restoreError struct {
	reason string
	err    error
}

func (e *restoreError) Error() string { return e.reason + ": " + e.err.Error() }
func (e *restoreError) Unwrap() error { return e.err }

// failRestore 는 까닭을 붙인 복원 실패다.
func failRestore(reason string, err error) error { return &restoreError{reason: reason, err: err} }

// restoreSession 은 회차 하나의 복원이다. 실패면 행 상태를 바꾸지 않고 세션 단위 백오프를 건다 — 신호
// chain_restore_failed(까닭 · 다음 시도 시각). 진전 없이 끝났으면(되살린 링크 0 — 꼬리에서 기다림 · 구멍 없음 · 이미 불일치로
// 판정한 무리) 실패가 아니지만 다음 깨움까지 최소 간격을 둔다(c10b 보안 M — 위치 모름 요청이 조각마다 같은 구멍을 다시 읽지
// 않게).
func (u *Uploader) restoreSession(w restoreWake) {
	lg := u.log.With("stream_id", w.streamID, "session_id", w.sessionID)
	progressed, err := u.restoreHoles(w, lg)
	switch {
	case err == nil && progressed:
		u.sessions.restoreSucceeded(w.sessionID)
		return
	case err == nil:
		u.sessions.restoreIdle(w.sessionID, u.now())
		return
	case errors.Is(err, errRestoreStopped):
		lg.Debug("chain_restore_stopped") // 종료 중 — 실패가 아니다(백오프 없음 · 다음 기동이 다시 한다)
		return
	}
	reason := "unknown"
	var re *restoreError
	if errors.As(err, &re) {
		reason = re.reason
	}
	next := u.sessions.restoreFailed(w.sessionID, u.now())
	lg.Warn("chain_restore_failed", "reason", reason, "err", err.Error(),
		"next_attempt_at", next.UTC().Format(time.RFC3339))
}

// restoreHoles 는 회차의 구멍을 DB 에서 열거해(r2 — cx-N1: 깨운 요청의 seq 를 목표로 병합하지 않는다) 닻이 같은 무리마다
// 되살린다. 무리 사이에는 uploaded 행이 끼어 닻이 다르다. progressed 는 되살린 링크가 하나라도 있는가다.
//
// 한 무리의 결정적 실패(닻 · 회차 까닭 — transientRestoreFailure 밖)는 그 무리만 건너뛰고 뒤 무리를 이어 되살린 뒤 첫 실패를
// 돌려준다(c10b 보안 M — 첫 오류에서 돌아가면 뒤 무리가 백오프 상한까지 막힌다). 무리끼리는 닻이 달라 서로의 재료가 아니다.
// 일시 실패는 바로 돌려준다 — 장애 동안 무리마다 같은 실패를 되풀이하지 않는다.
func (u *Uploader) restoreHoles(w restoreWake, lg *slog.Logger) (progressed bool, err error) {
	ledger, _, _ := u.restoreDeps()
	ctx, cancel := u.dbCtx()
	holes, err := ledger.PlaybackHoles(ctx, w.sessionID)
	cancel()
	if err != nil {
		return false, failRestore("ledger", err)
	}
	groups := groupHoles(holes)
	var first error
	for i, g := range groups {
		if u.restoreStopping() {
			return progressed, errRestoreStopped
		}
		limit := g.last + restoreLookahead
		if i+1 < len(groups) {
			limit = min(limit, groups[i+1].first-1)
		}
		ok, err := u.restoreGroup(w, g, limit, lg)
		switch {
		case err == nil:
			progressed = progressed || ok
		case errors.Is(err, errRestoreStopped) || transientRestoreFailure(err):
			return progressed, err
		case first == nil:
			first = err
		}
	}
	return progressed, first
}

// transientRestoreFailure 는 err 가 다시 하면 풀릴 수 있는 실패(장부 · S3 · 로컬 읽기)인가다. 까닭을 모르는 오류도 일시
// 실패로 친다 — 모르는 실패로 뒤 무리를 돌리지 않는다.
func transientRestoreFailure(err error) bool {
	var re *restoreError
	if !errors.As(err, &re) {
		return true
	}
	switch re.reason {
	case "ledger", "s3_get", "local_read":
		return true
	}
	return false
}

// holeGroup 은 닻이 같은 구멍 한 무리다(seq 순).
type holeGroup struct {
	anchor      *int64
	first, last int64
	seqs        map[int64]bool
}

// groupHoles 는 seq 순 구멍을 닻이 같은 무리로 묶는다 — uploaded 행이 사이에 끼면 닻이 달라진다.
func groupHoles(holes []index.PlaybackHole) []holeGroup {
	var out []holeGroup
	for _, h := range holes {
		if n := len(out); n > 0 && sameAnchor(out[n-1].anchor, h.AnchorSeq) {
			out[n-1].last = h.Seq
			out[n-1].seqs[h.Seq] = true
			continue
		}
		out = append(out, holeGroup{anchor: h.AnchorSeq, first: h.Seq, last: h.Seq, seqs: map[int64]bool{h.Seq: true}})
	}
	return out
}

func sameAnchor(a, b *int64) bool {
	return (a == nil && b == nil) || (a != nil && b != nil && *a == *b)
}

// sameInstanceLink 는 복원 접기의 「같은 녹화기 이어짐」 이다 — 정의는 index.SameInstance 한 자리이고(실시간 사슬의
// indexer.sameInstance 와 같은 옮김), 링크 · 재료의 표식 · 번호 칸을 옮겨 부를 뿐이다.
func sameInstanceLink(prev playback.Link, cur playback.SourceTiming) bool {
	return index.SameInstance(
		index.TimingFacts{Recorder: prev.Recorder, Number: prev.Number},
		index.TimingFacts{Recorder: cur.Recorder, Number: cur.Number})
}

// s3Ctx 는 S3 호출 한 번의 문맥이다(getTimeout · Shutdown 5단계가 끊는다).
func (u *Uploader) s3Ctx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(u.putCtx, getTimeout)
}

// dbCtx 는 장부 호출 한 번의 문맥이다(MarkTimeout · Shutdown 6단계가 끊는다).
func (u *Uploader) dbCtx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(u.markRoot, u.opt.MarkTimeout)
}

// errAnchorPinned 는 닻의 ② 에 mtxi 가 없다는 뜻이다 — 고정 행의 기대 mtxi 는 그 앞 행에 기대므로 객체만으로 되짚지 못한다.
// 부르는 쪽(backAnchor)은 더 앞의 mtxi 있는 uploaded 행을 닻으로 삼아 고정 행을 다시 접는다.
var errAnchorPinned = errors.New("닻의 ② 에 mtxi 가 없다")

// anchorErr 는 닻 되살리기 실패를 까닭과 함께 감싼다.
func anchorErr(seq int64, reason string, err error) error {
	return failRestore(reason, fmt.Errorf("닻 seq %d: %w", seq, err))
}
