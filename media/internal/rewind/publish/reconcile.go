package publish

// 화해(Reconcile) — 설계 4.4.3 R1–R4 · 계획 4.5 A1 결정 2 · 5 · 체크리스트 A-3 · J15. 본문을 읽지 않는다: Head 의
// ETag 와 메타 8키만으로 P 를 되살리고(재기동 뒤 원천 — 결정 5), fence 를 쥐었을 때만 DB 를 그 판에 맞춘다.

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
)

// r3ReconcileSQL 은 R3 DB 화해다(설계 4.4.3 R3 · 계획 4.5 A1 결정 2 · G8 증명 ③) — fence 를 쥐었을 때만 쓴다.
// 세대와 마지막 seq 는 max 로만 올리고(DB 세대는 줄지 않는다), ETag 와 DISC-SEQ(base — 파생값)는 Head 가 본
// 판의 값으로 둔다. terminal 승격(frozen)은 M6 이다. 돌려받는 세대가 다음 P0 의 $genSeen 이다.
const r3ReconcileSQL = `
UPDATE stream_sessions
   SET manifest_gen       = GREATEST(manifest_gen, $3),
       published_seq      = GREATEST(published_seq, $4),
       manifest_etag      = $5,
       discontinuity_base = $6
 WHERE session_id = $1 AND writer_fence = $2 AND fence_expires_at > now()
RETURNING manifest_gen`

// r4ClearETagSQL 은 R4 다(설계 4.4.3 R4 「최초 생성 경로로 표시」 · 체크리스트 J15) — 객체가 없으면 DB
// manifest_etag 를 비워 다음 P0 이 최초 생성 경로(IfAbsent)를 돌려주게 한다. 표시가 메모리에만 남으면 다음 P0
// 이 옛 ETag 를 돌려주고 출처 대조(결정 8)가 매 틱 어긋나 발행이 다시 서지 않는다(외부 삭제 · 버킷 전환). 다른
// 열은 건드리지 않고 fence 를 쥐었을 때만 쓴다(R3 과 같은 형).
const r4ClearETagSQL = `
UPDATE stream_sessions
   SET manifest_etag = NULL
 WHERE session_id = $1 AND writer_fence = $2 AND fence_expires_at > now()
RETURNING manifest_gen`

// reconcile 은 R1 부터 한 번 화해한다 — Head 는 T_pub 마감의 ctx 로 부른다(판단 J8 · 커밋 2 리뷰 인계 — Store 는
// 스스로 시간을 재지 않는다). Head 가 실패하면(403 포함) head_failed 로 남기고 화해를 다음 틱에 다시 한다 —
// Head 가 돌아올 때까지 그 스트림 발행이 선다(계획 7절 8). 부른 쪽 ctx 가 끝나 실패했으면 아무것도 남기지 않는다
// (stopped). 끝까지 맞췄으면 참이다.
func (t *tick) reconcile(ctx context.Context) bool {
	hctx, cancel := context.WithTimeout(ctx, t.p.opt.PublishTimeout)
	stat, err := t.p.store.Head(hctx, t.key)
	cancel()
	if err != nil {
		if t.stopped(ctx) {
			return false
		}
		t.halt(ctx, reasonHeadFailed, "err", err.Error())
		t.out.State.ReconcileDue = true
		return false
	}
	return t.reconcileStat(ctx, stat)
}

// reconcileStat 은 Head 결과 stat 으로 R2–R4 를 한다.
//
//	객체 없음   R4 — P 를 비우고(최초 생성 경로) DB manifest_etag 를 비운다
//	객체 있음   R2 — 메타 8키와 ETag 를 엄격하게 읽어 P 로 삼고, R3 — DB 를 그 판에 맞춘다
//
// 메타 · ETag 가 없거나 해석에 실패하면(parseMeta · checkETag) 발행을 멈춘다(meta_invalid) — P 는 nil 로 두지
// 않는다. nil 이면 S1 · S2 · S6 이 견줄 것 없이 통과해 버린다(결정 5). 받을 수 없는 값은 DB 에도 싣지 않는다(보안
// r4 M-1). R3 · R4 가 0행이면 fence 를 잃은 것이다(인계 — 로그 없음). 다음 틱은 획득부터 한다(남이 쥐었으면 조용히
// 물러난다 — 정상 경쟁). P0 0행에서 시작한 화해면 p0_no_row 가 이미 남았다. 끝까지 맞췄으면 참이고 화해 예약을 푼다.
func (t *tick) reconcileStat(ctx context.Context, stat Stat) bool {
	st := &t.out.State
	st.ReconcileDue = true
	if !stat.Exists {
		st.Prev = nil
		return t.reconcileDB(ctx, r4ClearETagSQL, t.session, t.p.opt.Writer)
	}
	desc, err := parseMeta(stat.Meta, t.session)
	if err == nil {
		err = checkETag(stat.ETag)
	}
	if err != nil {
		t.halt(ctx, reasonMetaInvalid, "err", err.Error())
		return false
	}
	st.Prev = &Manifest{Published: desc, ETag: stat.ETag}
	return t.reconcileDB(ctx, r3ReconcileSQL, t.session, t.p.opt.Writer,
		desc.Gen, desc.PublishedSeq, stat.ETag, desc.DiscontinuitySequence)
}

// reconcileDB 는 R3 · R4 한 문장을 보내고 돌려받은 세대를 상태에 적는다. 0행이면 fence 를 잃었다고 적는다. 표에
// 갈래가 없는 DB 오류는 Outcome.Err 로 돌려준다 — 화해는 다음 틱에 다시 한다.
func (t *tick) reconcileDB(ctx context.Context, sql string, args ...any) bool {
	dctx, cancel := t.p.stmtCtx(ctx)
	defer cancel()
	var gen int64
	err := t.p.pool.QueryRow(dctx, sql, args...).Scan(&gen)
	st := &t.out.State
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		st.FenceHeld = false
		return false
	case err != nil:
		t.out.Err = fmt.Errorf("publish: 화해 DB 쓰기 session=%q: %w", t.session, err)
		return false
	}
	st.Gen, st.FenceHeld, st.ReconcileDue = gen, true, false
	return true
}
