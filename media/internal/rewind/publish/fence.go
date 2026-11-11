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
   SET writer_fence = $2, fence_expires_at = pg_catalog.now() + $3::interval
 WHERE session_id = $1
   AND (writer_fence IS NULL OR fence_expires_at < pg_catalog.now() OR writer_fence = $2)`

// fenceRenewSQL 은 lazy 갱신이다 — 아직 쥔 lease 만 늘린다. 끝난 lease 는 획득으로 다시 얻는다(그 사이 다른
// writer 가 썼을 수 있어 화해가 따라야 한다).
const fenceRenewSQL = `
UPDATE stream_sessions
   SET fence_expires_at = pg_catalog.now() + $3::interval
 WHERE session_id = $1 AND writer_fence = $2 AND fence_expires_at > pg_catalog.now()`

// fenceReleaseSQL 은 반납이다 — 이 writer 가 쥔 fence 를 모두 놓는다(shutdown · 다중 세션 보유).
//
// 잠금 순서 고정(POK-525 종결 r3 M1). 여러 스트림의 세션 행을 한 문장에 잠그는 것은 이 문장뿐이고, 하한 원장 올림(index
// raiseSQL)은 열린 회차의 세션 행을 FOR SHARE 로 (stream, round) 순 — edgebounds Writer 가 Go 의 바이트 비교로 정렬한
// 입력 순서 — 으로 잠근다. 순서 없는 UPDATE 는 힙(물리) 순서로 잠가 그 순서와 엇갈리면 순환 대기다 — 올림의
// lock_timeout(500ms — index raiseTxnLimitsSQL)이 deadlock_timeout(1초)보다 먼저 끊어 올림이 55P03 으로 끝나고, 그
// 상한이 없으면 교착(40P01)이다. 바깥 UPDATE 의 조인은 순서를 정하지 않으므로 안쪽 질의가 같은 순서로 먼저 잠근다 —
// ORDER BY 뒤에 FOR NO KEY UPDATE 가 돌고(비키 열 UPDATE 와 같은 강도 — 조각 INSERT 의 FK FOR KEY SHARE 는 막지
// 않는다), 정렬은 바이트 순(COLLATE "C")이다. DB 기본 정렬(en_US 등)은 대소문자 섞인 스트림 id 에서 바이트 순과
// 갈린다. 회차가 없는(round_id NULL — M6 전) 행은 그 스트림 끝에 서고 올림은 그 행을 잡지 않는다.
const fenceReleaseSQL = `
UPDATE stream_sessions s
   SET writer_fence = NULL
  FROM (SELECT t.session_id
          FROM stream_sessions t
         WHERE t.writer_fence = $1
         ORDER BY t.stream_id COLLATE pg_catalog."C", t.round_id COLLATE pg_catalog."C", t.session_id COLLATE pg_catalog."C"
           FOR NO KEY UPDATE OF t) k
 WHERE s.session_id = k.session_id AND s.writer_fence = $1`

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
