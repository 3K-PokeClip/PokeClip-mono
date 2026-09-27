package publish

// writer fence — 설계 6.2 의 획득 · lazy 갱신 · 반납이다(계획 부기 40 — 자리는 rewind/publish). 주 갱신은 P0 이
// 겸하고(lease 를 now() + Lease 로), 검증은 따로 묻지 않고 P0 · P2′ · P4 · R3 · R4 의 CAS 조건 안에서 한다. 다중
// 세션 보유를 허용한다 — 토큰 · CAS 가 회차별이다.

import (
	"context"
	"fmt"
)

// fenceAcquireSQL 은 fence 획득이다(설계 6.2 획득 행 그대로) — 비었거나 · lease 가 끝났거나 · 이미 내 것이면
// 얻는다. 반납된(NULL) fence 는 lease 끝을 기다리지 않고 얻는다(계획 4.5 A1 「반납 예외」).
const fenceAcquireSQL = `
UPDATE stream_sessions
   SET writer_fence = $2, fence_expires_at = now() + $3::interval
 WHERE session_id = $1
   AND (writer_fence IS NULL OR fence_expires_at < now() OR writer_fence = $2)`

// fenceRenewSQL 은 lazy 갱신이다 — 아직 쥔 lease 만 늘린다. 끝난 lease 는 획득으로 다시 얻는다(그 사이 다른
// writer 가 썼을 수 있어 화해가 따라야 한다).
const fenceRenewSQL = `
UPDATE stream_sessions
   SET fence_expires_at = now() + $3::interval
 WHERE session_id = $1 AND writer_fence = $2 AND fence_expires_at > now()`

// fenceReleaseSQL 은 반납이다 — 이 writer 가 쥔 fence 를 모두 놓는다(shutdown · 다중 세션 보유).
const fenceReleaseSQL = `UPDATE stream_sessions SET writer_fence = NULL WHERE writer_fence = $1`

// acquire 는 회차 sessionID 의 fence 를 얻으려 한다. 다른 writer 가 lease 안에서 쥐고 있으면 거짓이다(정상 경쟁).
// 얻은 뒤에는 부르는 쪽이 곧바로 화해한다(설계 6.2).
func (p *Publisher) acquire(ctx context.Context, sessionID string) (bool, error) {
	ctx, cancel := p.stmtCtx(ctx)
	defer cancel()
	tag, err := p.pool.Exec(ctx, fenceAcquireSQL, sessionID, p.opt.Writer, p.opt.Lease)
	if err != nil {
		return false, fmt.Errorf("publish: fence 획득 session=%q: %w", sessionID, err)
	}
	return tag.RowsAffected() == 1, nil
}

// RenewFence 는 회차 sessionID 의 lazy 갱신이다(설계 6.2 보조 갱신). 주 갱신은 P0 이 겸하고, 이 갱신은 마지막
// 갱신(st.RenewedAt)에서 LazyRenewAfter 가 지났을 때만 lease 를 늘린다 — 그 전에는 DB 에 아무것도 보내지 않는다.
// fence 를 쥐지 않은 상태면 할 일이 없다. 0행이면 fence 를 잃었다 — 다음 틱이 다시 얻는다.
func (p *Publisher) RenewFence(ctx context.Context, st State, sessionID string) Outcome {
	out := Outcome{State: st}
	if !st.FenceHeld {
		return out
	}
	sent := p.now()
	if sent.Sub(st.RenewedAt) < p.opt.LazyRenewAfter {
		return out
	}
	ctx, cancel := p.stmtCtx(ctx)
	defer cancel()
	tag, err := p.pool.Exec(ctx, fenceRenewSQL, sessionID, p.opt.Writer, p.opt.Lease)
	switch {
	case err != nil:
		out.Err = fmt.Errorf("publish: fence 갱신 session=%q: %w", sessionID, err)
	case tag.RowsAffected() == 1:
		out.State.RenewedAt = sent
	default:
		out.State.FenceHeld = false
	}
	return out
}

// Release 는 이 writer 가 쥔 fence 를 모두 놓는다 — shutdown 때 한 번 부른다(설계 6.2 반납). 반납 뒤 다른
// writer 는 lease 끝을 기다리지 않고 얻는다. 그 뒤에 닿는 것은 마지막 P3 의 늦은 적용뿐이고, 다음 writer 의
// 조건부 쓰기가 받는다(계획 4.5 A1 「반납 예외」). 루프 ctx 가 끝난 뒤 부르므로 끊기지 않은 반납용 ctx 를 넘긴다(예:
// context.WithTimeout(context.WithoutCancel(ctx), …)) — 끝난 ctx 로 부르면 반납 문장이 나가지 않아 후임이 lease 끝까지
// 기다린다.
func (p *Publisher) Release(ctx context.Context) error {
	ctx, cancel := p.stmtCtx(ctx)
	defer cancel()
	if _, err := p.pool.Exec(ctx, fenceReleaseSQL, p.opt.Writer); err != nil {
		return fmt.Errorf("publish: fence 반납 writer=%q: %w", p.opt.Writer, err)
	}
	return nil
}
