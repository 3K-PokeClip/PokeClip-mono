// Package endsignal 은 계약4 3-1 4D 의도적 종료 신호의 수신부다(POST /api/ingest/end-signal — POK-472).
//
// 요청마다 4C resolve 로 passphrase 를 받아 Bearer 파생 값을 맞춰 본 뒤 버린다(저장 · 캐시 없음). 인증된 신호만
// session.ApplyIntentionalEnd 로 넘기고, 커밋 뒤 표지를 받은편지함에 넣는다. 신호는 최선 노력이다 — 판단할 수 없으면
// 503 으로 돌려주고(플러그인이 3회까지 재시도) 그 이상 붙잡지 않는다.
//
// 어떤 로그 · 응답에도 Authorization 값 · 파생 값 · passphrase · 요청 본문이 실리지 않는다.
package endsignal

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/control"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// pattern 은 처리기의 경로다(계약4 3-1).
const pattern = "POST /api/ingest/end-signal"

// globalKey 는 전체 계수기의 고정 키다.
const globalKey = "4d"

// shedEvery 는 0 단계에서 흘린 503 을 묶어 한 줄로 내보내는 최소 간격이다(설계 6절).
const shedEvery = time.Second

// Options 는 처리기의 함수 칸과 수치다. 바깥 호출은 모두 함수 칸이다(시험이 가짜를 끼운다). 빈 칸은 New 가 거부한다.
type Options struct {
	Resolve    func(ctx context.Context, token string) (keyresolve.Verdict, error)                    // keyresolve.Client.Resolve
	Apply      func(ctx context.Context, e session.IntentionalEnd) (session.IntentionalResult, error) // ApplyIntentionalEnd(pool, o) 를 묶은 것
	Changed    index.PlaybackChanged                                                                  // 표지 — 커밋 결과를 모를 때도 부른다
	PostIntent func(streamID, sessionID string)                                                       // 계승 해제 요청(IntentInbox.Post)
	Now        func() time.Time                                                                       // 수신 시각(Media 시계) — StoppedAt 계산
	Log        *slog.Logger

	BodyTimeout    time.Duration // 1s — 처리기 진입부터 본문 끝까지(ResponseController.SetReadDeadline)
	ResolveTimeout time.Duration // 1s
	ApplyTimeout   time.Duration // 1.5s — 셋의 합 3.5s < control 서버 WriteTimeout 5s
	// 두 계수기의 로거는 nil 이다 — control_rate_limited 게이지를 세우면 내리는 쪽이 없다. 키당 초과는 end_signal
	// reason=rate_key, 전체 초과는 end_signal_shed reason=rate_global 묶음 줄이 센다(cmd/segment-indexer newEndSignalReceiver).
	PerKey *control.Limiter // NewLimiter(1, DefaultLimiterCells, Now, nil) — 키당 초당 1(인증 뒤에만 깎는다)
	Global *control.Limiter // NewLimiter(10, 1, Now, nil) — 전체 초당 10(고정 키 하나)
}

// validate 는 빈 함수 칸 · 0 이하 시한 · nil 계수기 · nil 로거를 거부한다.
func (o Options) validate() error {
	switch {
	case o.Resolve == nil, o.Apply == nil, o.Changed == nil, o.PostIntent == nil, o.Now == nil:
		return errors.New("endsignal: Options 의 함수 칸이 비었다")
	case o.Log == nil:
		return errors.New("endsignal: Options.Log 가 비었다")
	case o.BodyTimeout <= 0, o.ResolveTimeout <= 0, o.ApplyTimeout <= 0:
		return fmt.Errorf("endsignal: 시한은 0 보다 커야 한다 body=%v resolve=%v apply=%v",
			o.BodyTimeout, o.ResolveTimeout, o.ApplyTimeout)
	case o.PerKey == nil, o.Global == nil:
		return errors.New("endsignal: Options 의 계수기가 비었다")
	}
	return nil
}

// Receiver 는 4D 수신부다. Route 로 제어면 서버에 등록하고, 종료 때 Close 를 서버 Shutdown 보다 먼저 부른다.
type Receiver struct {
	o   Options
	sem chan struct{} // 동시 1 — 차 있으면 기다리지 않고 503

	stopCtx context.Context // Close 가 취소한다 — 진행 중 처리기의 ctx 가 따른다
	stop    context.CancelFunc

	mu     sync.Mutex // closed 와 wg.Add 를 한 임계 구역에 묶는다
	closed bool
	wg     sync.WaitGroup // 진행 중 처리기

	shedMu   sync.Mutex
	shed     map[string]int // 0 단계에서 흘린 503 — 사유별 계수
	shedLast time.Time      // 마지막으로 묶음을 내보낸 때
}

// New 는 함수 칸 · 수치를 검사해 Receiver 를 만든다. 빈 칸이면 오류다 — 조립이 기동을 멈춘다.
func New(o Options) (*Receiver, error) {
	if err := o.validate(); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Receiver{o: o, sem: make(chan struct{}, 1), stopCtx: ctx, stop: cancel, shed: map[string]int{}}, nil
}

// Route 는 startControl 의 extra 에 넣을 처리기다(Pattern "POST /api/ingest/end-signal"). 시한 · 머리 크기는 control 서버가 건다.
func (r *Receiver) Route() control.Route {
	return control.Route{Pattern: pattern, Handler: http.HandlerFunc(r.serve)}
}

// Close 는 종료 계약이다(설계 3절). 닫힘 표지를 세워 새 요청을 503 으로 돌리고, 진행 중 처리기의 ctx 를 취소한 뒤
// 그것들이 끝나기를 ctx 까지 기다린다(넘으면 ctx 오류). 남은 흘림 집계 줄을 내보낸다. 두 번 불러도 된다.
func (r *Receiver) Close(ctx context.Context) error {
	r.mu.Lock()
	r.closed = true
	r.mu.Unlock()
	r.stop()
	done := make(chan struct{})
	go func() {
		r.wg.Wait()
		close(done)
	}()
	var err error
	select {
	case <-done:
	case <-ctx.Done():
		err = ctx.Err()
	}
	r.flushShed()
	return err
}

// admit 은 0 단계의 닫힘 검사다. 닫히지 않았으면 같은 임계 구역에서 진행 중 계수를 올린다 — closed 가 선 뒤에는
// Add 가 없어 Close 의 Wait 와 겹치지 않는다.
func (r *Receiver) admit() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return false
	}
	r.wg.Add(1)
	return true
}

// serve 는 HTTP 처리기다. 0 단계(닫힘 · 전체 한도)에서 흘린 요청은 묶음 줄로만 세고, 그 밖은 요청마다 한 줄을 쓴다.
func (r *Receiver) serve(w http.ResponseWriter, req *http.Request) {
	if !r.admit() {
		r.shedOne("closing")
		writeStatus(w, http.StatusServiceUnavailable)
		return
	}
	defer r.wg.Done()
	if !r.o.Global.Allow(globalKey) {
		r.shedOne("rate_global")
		writeStatus(w, http.StatusServiceUnavailable)
		return
	}
	ctx, cancel := context.WithCancel(req.Context())
	defer cancel()
	stopAfter := context.AfterFunc(r.stopCtx, cancel)
	defer stopAfter()

	out := r.process(ctx, w, req)
	r.logOutcome(out)
	writeStatus(w, out.status)
}

// process 는 판정 순서 1–10 이다(설계 3절). 위에서 처음 걸리는 줄이 응답이다.
func (r *Receiver) process(ctx context.Context, w http.ResponseWriter, req *http.Request) outcome {
	sig, out, ok := r.readSignal(w, req, r.o.Now())
	if !ok {
		return out
	}
	bearer, ok := parseBearer(req.Header.Get("Authorization"))
	if !ok {
		return outcome{status: http.StatusUnauthorized, reason: "bad_auth_form", streamID: sig.token}
	}
	select {
	case r.sem <- struct{}{}:
		defer func() { <-r.sem }()
	default:
		return outcome{status: http.StatusServiceUnavailable, reason: "busy", streamID: sig.token}
	}
	if out, ok := r.authenticate(ctx, sig.token, bearer); !ok {
		return out
	}
	if !r.o.PerKey.Allow(sig.token) {
		return outcome{status: http.StatusTooManyRequests, reason: "rate_key", streamID: sig.token}
	}
	return r.apply(ctx, sig)
}

// authenticate 는 4–6 단계다 — resolve(시한 ResolveTimeout) · valid · 파생 값 대조. 거짓이면 out 이 응답이다.
func (r *Receiver) authenticate(ctx context.Context, token, bearer string) (outcome, bool) {
	rctx, cancel := context.WithTimeout(ctx, r.o.ResolveTimeout)
	defer cancel()
	v, err := r.o.Resolve(rctx, token)
	if err != nil {
		if r.stopCtx.Err() != nil {
			return outcome{status: http.StatusServiceUnavailable, reason: "closing", streamID: token}, false
		}
		return outcome{status: http.StatusServiceUnavailable, reason: "resolve_undecided", streamID: token, kind: resolveKind(err)}, false
	}
	if !v.Valid {
		return outcome{status: http.StatusUnauthorized, reason: "key_rejected", streamID: token, keyReason: v.Reason}, false
	}
	if !verifyBearer(v.Passphrase, bearer) {
		return outcome{status: http.StatusUnauthorized, reason: "mismatch", streamID: token}, false
	}
	return outcome{}, true
}

// resolveKind 는 resolve 오류의 로그 칸이다. UndecidedError 는 Kind 만 쓴다(그 타입이 본문 · URL 을 이미 뺀다).
func resolveKind(err error) string {
	var ue *keyresolve.UndecidedError
	switch {
	case errors.As(err, &ue):
		return ue.Kind
	case errors.Is(err, keyresolve.ErrInvalidPassphrase):
		return "invalid_passphrase"
	}
	return "error"
}

// apply 는 8–10 단계다. 오류여도 커밋 결과를 모를 수 있어 Changed 를 부른다(설계 3절 9 · M6 2.2 규칙 1 — 표지는
// 재조회라 더 불러도 같은 값이다). 재시도 가능한 미적용(contended)도 503 이다 — 위상 잠금 대기가 Apply 시한을 넘긴
// 4D 를 202 로 삼키지 않고 플러그인 재시도에 맡긴다(M6 11절). 커밋 전에 접혀 표지는 없다. PostIntent 는 성공으로
// RevokeSession 을 안 때만이다.
func (r *Receiver) apply(ctx context.Context, sig signal) outcome {
	actx, cancel := context.WithTimeout(ctx, r.o.ApplyTimeout)
	defer cancel()
	res, err := r.o.Apply(actx, session.IntentionalEnd{StreamID: sig.token, StoppedAt: sig.stoppedAt, ConnStartedAt: sig.connStartedAt})
	if err != nil {
		r.o.Changed(sig.token)
		return r.unavailable(actx, sig.token, "apply_failed", err)
	}
	if res.Retryable() {
		return r.unavailable(actx, sig.token, "contended", nil)
	}
	if res.Effect != "" {
		r.o.Changed(sig.token)
	}
	if res.RevokeSession != "" {
		r.o.PostIntent(sig.token, res.RevokeSession)
	}
	return outcome{status: http.StatusAccepted, reason: "accepted", streamID: sig.token}
}

// unavailable 은 Apply 갈래의 503 이다. 사유는 닫힘 · Apply 시한이 reason 보다 앞선다 — 시한 · 취소가 오류로 오든
// contended 로 접혀 오든 같은 사유로 남는다.
func (r *Receiver) unavailable(actx context.Context, token, reason string, err error) outcome {
	switch {
	case r.stopCtx.Err() != nil:
		reason = "closing"
	case actx.Err() != nil:
		reason = "apply_timeout"
	}
	return outcome{status: http.StatusServiceUnavailable, reason: reason, streamID: token, err: err}
}

// 본문 규칙(계약4 3-1).
const (
	maxBodyBytes = 4 << 10 // 정상 본문은 약 120B
	maxElapsedMs = 60000
)

// signal 은 형식 검사를 지난 신호다 — 시각은 수신 시각에서 되살렸다.
type signal struct {
	token         string // 장부 stream_id(= streamid 의 r 값)
	stoppedAt     time.Time
	connStartedAt time.Time
}

// readSignal 은 1 단계다. 본문 마감(진입 + BodyTimeout)을 걸고 4KiB 까지 읽어 해석한다. 마감을 못 걸면 본문을 읽지 않고
// 503, 마감에 걸리면 503, 그 밖의 실패는 400 이다.
func (r *Receiver) readSignal(w http.ResponseWriter, req *http.Request, receivedAt time.Time) (signal, outcome, bool) {
	if err := http.NewResponseController(w).SetReadDeadline(time.Now().Add(r.o.BodyTimeout)); err != nil {
		return signal{}, outcome{status: http.StatusServiceUnavailable, reason: "body_deadline_unsupported"}, false
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, req.Body, maxBodyBytes))
	if err != nil {
		if isTimeout(err) {
			return signal{}, outcome{status: http.StatusServiceUnavailable, reason: "body_timeout"}, false
		}
		return signal{}, outcome{status: http.StatusBadRequest, reason: "bad_request"}, false
	}
	sig, ok := parseSignal(raw, receivedAt)
	if !ok {
		return signal{}, outcome{status: http.StatusBadRequest, reason: "bad_request"}, false
	}
	return sig, outcome{}, true
}

// isTimeout 은 읽기 마감에 걸린 오류인지 본다.
func isTimeout(err error) bool {
	var ne net.Error
	return errors.Is(err, os.ErrDeadlineExceeded) || (errors.As(err, &ne) && ne.Timeout())
}

// parseSignal 은 본문을 해석해 범위를 검사한다. 필드 이름은 정확히 맞아야 하고(encoding/json 의 대소문자 무시 대조를
// 쓰지 않는다 — 계약은 streamId 를 다른 값으로 둔다) 모르는 필드는 무시한다. 정수 둘은 null · 문자열 · 소수를 받지 않는다.
func parseSignal(raw []byte, receivedAt time.Time) (signal, bool) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		return signal{}, false
	}
	var streamID string
	if err := json.Unmarshal(fields["streamid"], &streamID); err != nil {
		return signal{}, false
	}
	token, ok := streamkey.ParseStreamID(streamID)
	if !ok {
		return signal{}, false
	}
	elapsed, ok := jsonInt(fields["elapsedSinceStopMs"])
	if !ok || elapsed < 0 || elapsed > maxElapsedMs {
		return signal{}, false
	}
	duration, ok := jsonInt(fields["connectionDurationMs"])
	if !ok || duration < 0 {
		return signal{}, false
	}
	stoppedAt := receivedAt.Add(-time.Duration(elapsed) * time.Millisecond)
	return signal{token: token, stoppedAt: stoppedAt, connStartedAt: stoppedAt.Add(-millis(duration))}, true
}

// jsonInt 는 JSON 정수 하나다. 없음 · null · 정수 아님은 거짓이다.
func jsonInt(raw json.RawMessage) (int64, bool) {
	if raw == nil || string(raw) == "null" {
		return 0, false
	}
	var n int64
	if err := json.Unmarshal(raw, &n); err != nil {
		return 0, false
	}
	return n, true
}

// millis 는 0 이상의 밀리초를 기간으로 바꾼다. 곱이 int64 를 넘으면(9223372036855ms 이상) 최대 기간으로 자른다 — 넘친
// 곱은 음수가 되어 연결 시작이 미래로 간다.
func millis(ms int64) time.Duration {
	if ms > math.MaxInt64/int64(time.Millisecond) {
		return math.MaxInt64
	}
	return time.Duration(ms) * time.Millisecond
}

// outcome 은 처리한 요청 하나의 응답과 로그 칸이다.
type outcome struct {
	status    int
	reason    string
	streamID  string // 400 이면 비운다(형식 밖 값)
	kind      string // resolve_undecided 의 UndecidedError.Kind 등
	keyReason string // key_rejected 의 4C 이유(MALFORMED · NOT_FOUND · REVOKED)
	err       error  // apply 오류 — 서버 로그에만
}

// logOutcome 은 end_signal 한 줄이다. 202 INFO · 4xx WARN · 503 은 apply_failed · body_deadline_unsupported 만 ERROR 다.
func (r *Receiver) logOutcome(out outcome) {
	attrs := []any{"status", out.status, "reason", out.reason}
	if out.streamID != "" {
		attrs = append(attrs, "stream_id", out.streamID)
	}
	if out.kind != "" {
		attrs = append(attrs, "kind", out.kind)
	}
	if out.keyReason != "" {
		attrs = append(attrs, "key_reason", out.keyReason)
	}
	if out.err != nil {
		attrs = append(attrs, "err", out.err)
	}
	level := slog.LevelWarn
	switch {
	case out.status == http.StatusAccepted:
		level = slog.LevelInfo
	case out.reason == "apply_failed", out.reason == "body_deadline_unsupported":
		level = slog.LevelError
	}
	r.o.Log.Log(context.Background(), level, "end_signal", attrs...)
}

// errorBodies 는 상태별 고정 본문이다. 오류 원문 · 판정 사유는 싣지 않는다(서버 로그에만).
var errorBodies = map[int][]byte{
	http.StatusBadRequest:         []byte(`{"error":"bad_request"}` + "\n"),
	http.StatusUnauthorized:       []byte(`{"error":"unauthorized"}` + "\n"),
	http.StatusTooManyRequests:    []byte(`{"error":"rate_limited"}` + "\n"),
	http.StatusServiceUnavailable: []byte(`{"error":"unavailable"}` + "\n"),
}

// writeStatus 는 상태 코드와 고정 본문을 쓴다. 202 는 본문이 없다.
func writeStatus(w http.ResponseWriter, status int) {
	body, ok := errorBodies[status]
	if !ok {
		w.WriteHeader(status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

// shedOne 은 0 단계에서 흘린 503 하나를 센다. 마지막 내보냄에서 shedEvery 이상 지났으면 그때 묶음 줄을 낸다(타이머
// 고루틴 없음). 남은 묶음은 Close 가 내보낸다.
func (r *Receiver) shedOne(reason string) {
	r.shedMu.Lock()
	defer r.shedMu.Unlock()
	r.shed[reason]++
	if now := r.o.Now(); now.Sub(r.shedLast) >= shedEvery {
		r.emitShedLocked(now)
	}
}

// flushShed 는 남은 묶음을 내보낸다.
func (r *Receiver) flushShed() {
	r.shedMu.Lock()
	defer r.shedMu.Unlock()
	r.emitShedLocked(r.o.Now())
}

// emitShedLocked 는 사유별 묶음 줄을 내고 계수를 비운다. shedMu 를 쥔 채 부른다.
func (r *Receiver) emitShedLocked(now time.Time) {
	for _, reason := range []string{"closing", "rate_global"} {
		if n := r.shed[reason]; n > 0 {
			r.o.Log.Warn("end_signal_shed", "status", http.StatusServiceUnavailable, "reason", reason, "count", n)
		}
	}
	clear(r.shed)
	r.shedLast = now
}
