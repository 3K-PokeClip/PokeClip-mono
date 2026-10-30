package main

// 계약4 4D 종료 신호 수신부의 조립(POK-472 · 설계 2절 「설정 짝 규칙」 · 3절 「종료 계약」).
//
// 사이드카의 4C resolve 클라이언트는 하나다 — 이 수신부가 쓰고, 계약9 streamerId resolve(M6-8)가 같은 것을 받는다.
// 설정이 둘 다 비면 클라이언트도 수신부도 없다(미등록 404 · WARN end_signal_disabled).

import (
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/control"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/endsignal"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/secretfile"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// 4D 수신부 수치(설계 3 · 4절). 처리기 안 단계 시한의 합 3.5s 가 control 서버 WriteTimeout 5s 안이다.
const (
	endSignalBodyTimeout    = time.Second
	endSignalResolveTimeout = time.Second
	endSignalApplyTimeout   = 1500 * time.Millisecond
	// endSignalCloseTimeout 은 종료 때 진행 중 처리기를 기다리는 상한이다 — 본문 마감 1s + 응답 여유 0.5s.
	endSignalCloseTimeout = 1500 * time.Millisecond
	endSignalPerKeyRPS    = 1  // 키당 초당 — 정상은 멈춤 1번에 최대 3회이고 재시도 간격이 1s · 2s 다
	endSignalGlobalRPS    = 10 // 전체 초당 — 인증 전 폭주를 503 으로 묶는다
)

// newResolveClient 는 사이드카의 4C resolve 클라이언트다. 설정 둘 다 빈 값이면 (nil, nil) 이다(짝 규칙은 config 가 이미
// 봤다). 토큰 파일은 secretfile 규칙으로 읽고, 에러에는 env 이름만 싣는다(경로 · 내용 없음). 창구는 전용 창구(영값)다.
// 실패는 기동 실패다.
func newResolveClient(cfg config.Config) (*keyresolve.Client, error) {
	if cfg.ResolveURL == "" && cfg.ResolveTokenFile == "" {
		return nil, nil
	}
	raw, err := secretfile.Read("STREAM_KEY_RESOLVE_TOKEN_FILE", cfg.ResolveTokenFile, secretfile.Options{})
	if err != nil {
		return nil, err
	}
	return keyresolve.NewClient(keyresolve.ClientOptions{BaseURL: cfg.ResolveURL, Token: keyresolve.NewSecret(raw)})
}

// endSignalDeps 는 수신부의 바깥 칸이다 — 운영은 newEndSignal 이, 시험은 가짜로 채운다.
type endSignalDeps struct {
	resolve    func(context.Context, string) (keyresolve.Verdict, error)
	apply      func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error)
	changed    index.PlaybackChanged
	postIntent func(streamID, sessionID string)
	log        *slog.Logger
	now        func() time.Time // 수신 시각 · 두 계수기의 시계 — nil 이면 time.Now(시험이 고정한다)
}

// newEndSignalReceiver 는 운영 수치와 자기 계수기 둘로 수신부를 만든다. 계수기는 이 수신부만의 것이라 청소 고루틴 없이
// 칸 상한(DefaultLimiterCells)으로만 묶인다. 같은 까닭으로 계수기의 log 는 nil 이다(cc M1) — control_rate_limited
// 게이지의 내림 줄은 청소 · 칸 축출만 쓰는데 여기엔 청소가 없고 전체 계수기는 칸 하나라 축출도 없어, 한 번 세우면 1 로
// 남는다. 초과는 수신부 자신의 줄이 센다 — 키당은 요청마다 end_signal reason=rate_key, 전체는 0 단계라 묶음 줄
// end_signal_shed reason=rate_global count=… 이다.
func newEndSignalReceiver(d endSignalDeps) (*endsignal.Receiver, error) {
	now := d.now
	if now == nil {
		now = time.Now
	}
	return endsignal.New(endsignal.Options{
		Resolve: d.resolve, Apply: d.apply, Changed: d.changed, PostIntent: d.postIntent, Now: now, Log: d.log,
		BodyTimeout: endSignalBodyTimeout, ResolveTimeout: endSignalResolveTimeout, ApplyTimeout: endSignalApplyTimeout,
		PerKey: control.NewLimiter(endSignalPerKeyRPS, control.DefaultLimiterCells, now, nil),
		Global: control.NewLimiter(endSignalGlobalRPS, 1, now, nil),
	})
}

// newEndSignal 은 client 가 있으면 수신부를 만든다. 없으면 WARN end_signal_disabled reason=no_resolve 를 남기고 (nil, nil)
// 이다 — prod 에서 조용히 꺼지면 4D 가 모두 404 라 경보 대상이다.
func newEndSignal(client *keyresolve.Client, apply func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error),
	changed index.PlaybackChanged, postIntent func(streamID, sessionID string), log *slog.Logger,
) (*endsignal.Receiver, error) {
	if client == nil {
		log.Warn("end_signal_disabled", "reason", "no_resolve",
			"note", "STREAM_KEY_RESOLVE_URL · STREAM_KEY_RESOLVE_TOKEN_FILE 이 비어 있다 — 4D 종료 신호는 404 이고 보장 시간 판정만 돈다")
		return nil, nil
	}
	return newEndSignalReceiver(endSignalDeps{resolve: client.Resolve, apply: apply, changed: changed, postIntent: postIntent, log: log})
}

// intentApplier 는 인덱서 풀로 4D 를 적용하는 Apply 칸이다(session.ApplyIntentionalEnd 를 묶는다).
func intentApplier(pool *pgxpool.Pool, o session.IntentOptions) func(context.Context, session.IntentionalEnd) (session.IntentionalResult, error) {
	return func(ctx context.Context, e session.IntentionalEnd) (session.IntentionalResult, error) {
		return session.ApplyIntentionalEnd(ctx, pool, e, o)
	}
}

// startControlWithEndSignal 은 recv 의 Route 를 extra 로 넣어 제어면을 띄운다(recv 가 nil 이면 넣지 않는다). 돌려준 stop 은
// 종료 계약 차례다 — recv.Close(멈출 때 만든 1.5s ctx) → 제어면 서버 Shutdown. 서버 Shutdown 은 grace 뒤 처리기를
// 멈추지 않으므로, 진행 중 4D 처리기를 먼저 취소 · 대기해 서버 뒤의 풀 Close 가 그 처리기를 만나지 않게 한다(M5 3.7 LIFO).
func startControlWithEndSignal(cfg config.Config, board *status.Holder, log *slog.Logger, round control.RoundLookup, recv *endsignal.Receiver) (stop func()) {
	if recv == nil {
		return startControl(cfg, board, log, round)
	}
	stopControl := startControl(cfg, board, log, round, recv.Route())
	return func() {
		closeEndSignal(recv, log)
		stopControl()
	}
}

// closeEndSignal 은 recv.Close 를 멈추는 순간 만든 시한 ctx 로 부른다 — 기동 때 만들면 시한이 그때부터 흐른다.
func closeEndSignal(recv *endsignal.Receiver, log *slog.Logger) {
	ctx, cancel := context.WithTimeout(context.Background(), endSignalCloseTimeout)
	defer cancel()
	if err := recv.Close(ctx); err != nil {
		log.Warn("end_signal_close_timeout", "err", err)
	}
}
