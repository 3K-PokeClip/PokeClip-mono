package main

// 정산 몫 조립(POK-196 M6-3b 커밋 ③ — 설계 M6 3.2 차선 · 주기 배선 · 10절 함수 칸). 종료 문장의 옵션(회차 닫힘 시한 · 신호)과
// 완결 평가 칸(계약9 ended outbox)은 run 이 여기서 한 번 만들고, EndLive · 4D 회차 닫힘 칸 · 정산 걸음이 같은 값을 쓴다.
//
// 완결 평가 칸은 broadcast.EnqueueEndedIfComplete 를 게이트와 묶은 것이다. 게이트는 M6-8 커밋 ④ 가 설정에서 채운다 — 그 전의
// 영값 게이트는 꺼짐이라 outbox 문장을 보내지 않는다(D45 — 켜기 전 회차는 발행하지 않는다).

import (
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/finalize"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// closeSlack 는 사슬 · 회차 닫힘 여유다(K8 확정 60초) — 재접속 창(cfg.ReconnectWindow) 뒤 이만큼 더 기다려 닫는다. config 에 두지
// 않는 것은 intentStopSlack 과 같은 까닭이다(계약4 3-2 판정 규칙의 일부).
const closeSlack = 60 * time.Second

// finalizeScanEvery 는 정산 · 회차 스캔 주기다(설계 3.2 — 30초).
const finalizeScanEvery = 30 * time.Second

// finalizeAssembly 는 종료 문장의 옵션과 완결 평가 칸이다.
type finalizeAssembly struct {
	close   index.CloseOptions
	enqueue index.EndedEnqueuer
}

// newFinalizeAssembly 는 설정의 재접속 창과 영값 게이트(꺼짐 — M6-8 이 채운다)로 조립한다.
func newFinalizeAssembly(cfg config.Config, log *slog.Logger) finalizeAssembly {
	return finalizeAssembly{
		close:   index.CloseOptions{Window: cfg.ReconnectWindow, Slack: closeSlack, Log: log},
		enqueue: newEndedEnqueuer(broadcast.Gate{}),
	}
}

// newEndedEnqueuer 는 게이트 g 의 완결 평가 칸이다.
func newEndedEnqueuer(g broadcast.Gate) index.EndedEnqueuer {
	return func(ctx context.Context, tx pgx.Tx, round string) error {
		_, err := broadcast.EnqueueEndedIfComplete(ctx, tx, g, round)
		return err
	}
}

// endLive 는 루프의 4.1 전이 손잡이다(rewindLoop.endLive) — 표식이 있으면 같은 트랜잭션에서 회차를 닫는다.
func (a finalizeAssembly) endLive(pool *pgxpool.Pool) func(ctx context.Context, streamID string) (string, error) {
	return func(ctx context.Context, streamID string) (string, error) {
		return index.EndLive(ctx, pool, streamID, a.close, a.enqueue)
	}
}

// gapRunEnd 는 L3 종료 손잡이다(rewindLoop.endGapRun) — 스위치(REWIND_GAP_RUN_END)가 꺼져 있으면 nil 이라 루프가 끝내지
// 않는다(kty K5 — 기본 끔). 표식이 있으면 EndLive 와 같이 사유 intentional 로 끝내며 회차를 닫는다.
func (a finalizeAssembly) gapRunEnd(on bool, pool *pgxpool.Pool) func(ctx context.Context, streamID, sessionID string) (string, error) {
	if !on {
		return nil
	}
	return func(ctx context.Context, streamID, sessionID string) (string, error) {
		return index.EndGapRun(ctx, pool, streamID, sessionID, a.close, a.enqueue)
	}
}

// closeRound 는 4D 적용의 회차 닫힘 칸이다(session.IntentOptions.CloseRound — 설계 10절 「M6-3b 가 CloseRound 칸을 채운다」).
// upgraded 는 그 트랜잭션에서 회차를 닫고 완결을 평가하고, round_split 은 R 을 직접 닫은 뒤라 완결만 평가된다.
func (a finalizeAssembly) closeRound() func(ctx context.Context, tx pgx.Tx, round string) error {
	return func(ctx context.Context, tx pgx.Tx, round string) error {
		_, err := index.TryCloseRound(ctx, tx, round, a.close, a.enqueue)
		return err
	}
}

// finalizeMode 는 정산 판정의 발행 모드다 — 판별자는 설정값이다(3.2). 버킷이 비면 꺼짐, 버킷이 있는데 발행자 조립이 실패했으면
// 강등, 성공이면 정상이다. 발행자 포인터가 nil 인가로 가르지 않는다(꺼짐과 강등이 둘 다 nil 이다).
func finalizeMode(bucket string, assembled bool) finalize.Mode {
	switch {
	case bucket == "":
		return finalize.ModeOff
	case !assembled:
		return finalize.ModeDegraded
	}
	return finalize.ModePublishing
}

// wire 는 r 에 정산 몫을 채운다 — run 이 발행 조립(assemblePublishing) 뒤 · 루프 기동 전에 한 번 부른다. 차선은 조립 밖에서
// 이미 채워져 있어야 한다(run). 상태 전달 sink 는 M5 받은편지함(assembleStatus 가 채운 finals)이다.
func (a finalizeAssembly) wire(r *rewindLoop, cfg config.Config, pool *pgxpool.Pool, inbox *session.PlaybackInbox, intents *session.IntentInbox) {
	var sink index.PlaybackSink
	if r.finals != nil {
		sink = r.finals.Post
	}
	r.fin = newFinalizer(finalizerOptions{
		Mode: finalizeMode(cfg.S3.Bucket, r.pub != nil), Every: finalizeScanEvery, CloseAfter: a.close.Window + a.close.Slack,
		Work: newFinalizeWork(pool, r.pub, a.close, a.enqueue), Inbox: inbox, Intents: intents, Sink: sink,
	})
}
