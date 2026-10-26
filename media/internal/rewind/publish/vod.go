package publish

// VOD 동결 — M6 설계 2.1 FreezeVOD · 3.1 phase 3 · 비귀속 행(POK-196 M6-2 커밋 ③).
//
// 닫힌 사슬 하나에 VOD 문서 하나다(K1 — 키는 머리 세션). 새 URL 의 새 문서라 기존 목록의 변경이 아니고, 한 번만 쓴다
// (IfAbsent · RFC 8216 4.3.3.5 VOD 불변). 412 면 Head 의 pc-body-sha256 이 다시 렌더한 본문과 같을 때만 그 판을 채택한다
// (D7 — 쓰고 확정 전에 멈춘 시도의 판). 다시 렌더가 같은 본문을 내도록, 렌더 전에 GAP 으로 실을 행을 원장에 먼저 적는다
// (포기 등재는 추가만 — D17 4.5.4 재현 가능성). 그 뒤 ③ 이 늦게 닿아도 원장 우선이라 같은 GAP 줄이다. 그래서 정산 창
// 안에서는 ③ 미착지 행을 GAP 원장 유무와 무관하게 기다린다 — 창 안에 닿을 조각을 VOD 에 영구 GAP 으로 굳히지 않는다.
//
// fence 를 쓰지 않는다. 쓰기는 새 키의 IfAbsent 하나와 vod_state='freezing' 조건의 확정 CAS 뿐이라 두 writer 가 겹쳐도
// 한 판만 남고 장부는 그 판으로만 확정된다. 다만 같은 본문을 내려면 두 writer 가 같은 장부를 봐야 한다 — 그래서 GAP 등재
// 트랜잭션이 사슬 꼬리 행을 잠그고 장부를 다시 읽어 그 값으로 plan · 렌더한다(잠금 → 재독 → 등재 → 커밋 → PUT · kty ⑧).
// 옛 스냅숏의 writer 는 앞 writer 의 커밋 뒤 장부를 읽으므로, 앞 writer 가 GAP 없이 쓴 판에 GAP 을 등재하지 않고 앞
// writer 가 적은 GAP 원장(원장 우선)을 그대로 싣는다(cx r1 H1). 잠금은 꼬리 행 하나다 — 설계 3.3 순서의 ② 자리이고
// 위상 잠금(①)은 잡지 않는다.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"slices"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/playback"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// VOD 재시도 · 실패 사유 — FreezeOutcome.Reason 값이다(DVR 과 같은 이름은 freeze.go 의 것을 쓴다).
const (
	vodUnsettled    = "unsettled"       // 정산 전(③ 미착지 행) — 포기 없이는 쓰지 않는다
	vodForeign      = "foreign_session" // 사슬 범위 안에 사슬 밖 회차의 행(설계 3.1 — 구조상 없어야 함)
	vodChainChanged = "chain_changed"   // 부른 쪽 사슬이 장부의 사슬과 다르다
	vodTooLarge     = "too_large"       // 본문이 MaxVODBodyBytes 를 넘는다(설계 3.1 크기 · 한계)
	vodConflict     = "vod_conflict"    // 같은 키에 다른 본문이 있다
	vodLedgerWrite  = "gap_ledger"      // 렌더 전 GAP 원장 쓰기 실패
	vodContended    = "contended"       // 꼬리 행 잠금이 lock_timeout(5초)에 걸렸다(55P03)
)

// vodTxnLimitsSQL 은 등재 트랜잭션의 지역 상한이다(index.setTxnLimitsSQL 과 같은 값) — lock_timeout 5초가 꼬리 잠금
// 대기 하나의 상한이고 넘기면 55P03 → 재시도(contended)다.
const vodTxnLimitsSQL = `
SELECT pg_catalog.set_config('lock_timeout','5s',true), pg_catalog.set_config('statement_timeout','10s',true)`

// vodLockTailSQL 은 사슬 꼬리 행 잠금이다(설계 3.3 순서의 ② — CloseChain 이 잡는 행과 같다).
const vodLockTailSQL = `SELECT 1 FROM stream_sessions WHERE session_id = $1 AND stream_id = $2 FOR UPDATE`

// vodMarkGapSQL 은 렌더 전 GAP 원장 적기다 — 행 하나를 GAP 으로 넣는다(이미 있으면 그대로 — 원장 우선). $4 사유는
// mixed_boundary(비귀속 행 · 회차는 앞 사슬 세션) · settle_deadline(정산 창 만료 미착지 행)이고 발행 상태는
// vod_abandoned 다(설계 3.1 phase 3).
const vodMarkGapSQL = `
INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason, published_state)
VALUES ($1, $2, $3, $4, 'vod_abandoned')
ON CONFLICT (stream_id, seq) DO NOTHING`

// vodAbandonGapsSQL 은 사슬 범위 [$2, $3] 의 GAP 원장 행 가운데 조각이 지금도 미착지인 것을 vod_abandoned 로 바꾼다 —
// VOD 가 그 조각을 버렸다(설계 3.1 phase 3 「DVR GAP 행 중 지금도 미착지」).
const vodAbandonGapsSQL = `
UPDATE stream_published_gaps g
   SET published_state = 'vod_abandoned'
  FROM stream_segments s
 WHERE g.stream_id = $1 AND g.seq BETWEEN $2 AND $3 AND g.published_state <> 'vod_abandoned'
   AND s.stream_id = g.stream_id AND s.seq = g.seq AND s.playback_upload_state <> 'uploaded'`

// vodConfirmSQL 은 VOD 확정이다 — 사슬 세션 $2 가운데 아직 freezing 인 행에 VOD 열을 쓴다. 바꾼 행 수가 사슬 수와 다르면
// 부르는 쪽이 되돌린다(트랜잭션).
const vodConfirmSQL = `
WITH upd AS (
    UPDATE stream_sessions
       SET vod_state = $3, vod_s3_key = $4, vod_etag = $5, vod_sha256 = $6, vod_first_seq = $7, vod_last_seq = $8
     WHERE stream_id = $1 AND session_id = ANY($2) AND vod_state = 'freezing'
    RETURNING 1
)
SELECT pg_catalog.count(*) FROM upd`

// vodStateSQL 은 확정이 0행일 때 꼬리의 VOD 상태를 읽는다 — 다른 writer 가 먼저 확정했는가.
const vodStateSQL = `SELECT vod_state FROM stream_sessions WHERE session_id = $1 AND stream_id = $2`

// FreezeVOD 는 닫힌 사슬의 VOD 문서를 IfAbsent 로 한 번 쓰고 사슬 전 세션의 vod_* 를 확정한다(phase 3).
// 새 URL 의 새 문서라 기존 목록의 변경이 아니다. 412 면 Head 의 pc-body-sha256 이
// 재렌더 값과 같을 때만 채택한다.
//
// chain 의 StreamID · Tail · SessionIDs 를 쓴다(SessionIDs 는 사슬을 닫은 집합 — 장부와 다르면 실패). abandon 은 정산 창이
// 끝났는가다 — 참이면 미착지 행을 GAP 줄로 싣고(settle_deadline) 거짓이면 미착지 행이 있을 때 재시도다. 문서는
// 사슬 범위(사슬 세션 행의 첫 seq 부터 끝 seq · 컷오프 아래는 뺀다) 전체 · KindVOD · Ended 다. 행이 없으면 빈 동결이다.
// GAP 줄이 하나라도 있으면 frozen_with_gaps 다.
func (p *Publisher) FreezeVOD(ctx context.Context, chain index.Chain, abandon bool) FreezeOutcome {
	if len(chain.SessionIDs) == 0 {
		return vodFailed(vodChainChanged, errors.New("publish: 사슬 세션 목록이 비었다"))
	}
	key, err := vodKey(chain.StreamID, chain.SessionIDs[0])
	if err != nil {
		return vodFailed(freezeRender, err)
	}
	v := &vodJob{p: p, chain: chain, key: key}
	// 잠금 밖 첫 읽기 — 재시도 · 실패 · 빈 동결은 꼬리를 잠그지 않고 가른다(정산 창 동안 스캔이 되풀이해 부른다).
	_, plan, out, ok := v.load(ctx, abandon)
	if !ok {
		return out
	}
	if len(plan.rows) == 0 {
		return v.confirm(ctx, ResultEmpty, nil, "", nil)
	}
	l, plan, out, ok := v.markGaps(ctx, abandon)
	switch {
	case !ok:
		return out
	case len(plan.rows) == 0:
		return v.confirm(ctx, ResultEmpty, nil, "", nil)
	}
	return v.write(ctx, l, plan)
}

// vodJob 은 VOD 동결 한 번의 문맥이다.
type vodJob struct {
	p     *Publisher
	chain index.Chain
	key   string
}

// vodPlan 은 장부에서 고른 VOD 렌더 행과 렌더 전에 원장에 적을 GAP 이다.
type vodPlan struct {
	rows []boundary.Row
	// marks 는 렌더 전에 원장에 넣을 GAP 행이다(seq · 회차 · 사유).
	marks []vodMark
	// abandon 은 정산 창이 끝났는가다 — 참일 때만 범위의 미착지 GAP 원장 행을 vod_abandoned 로 바꾼다.
	abandon bool
}

type vodMark struct {
	seq     int64
	session string
	reason  string
}

// plan 은 장부 l 의 행을 VOD 렌더 행으로 바꾼다(설계 3.1 비귀속 행 · D57).
//
//	사슬 세션 행 · ③ 미착지 · 포기 아님  재시도(정산 전 — DVR GAP 원장이 있어도 · 설계 3.1 phase 2)
//	사슬 세션 행 · settled               그대로(포기면 DVR GAP 원장 행도 여기 — markGaps 가 vod_abandoned 로 바꾼다)
//	사슬 세션 행 · 미착지 · 포기          GAP(settle_deadline)
//	비귀속 행(세션 NULL)         GAP(mixed_boundary) — 회차는 앞 사슬 세션 · PDT 는 앞 행 PDT + 길이 · URI 는 ③ 키 이름
//	사슬 밖 회차 행              실패(foreign_session)
func (v *vodJob) plan(l index.ChainLedger, abandon bool) (vodPlan, FreezeOutcome, bool) {
	members := map[string]bool{}
	for _, id := range l.Chain.SessionIDs {
		members[id] = true
	}
	plan := vodPlan{abandon: abandon}
	for _, lr := range l.Rows {
		if l.HasCutoff && lr.Seq < l.CutoffSeq {
			continue
		}
		r := boundaryRow(lr)
		switch {
		case r.SessionID != "" && !members[r.SessionID]:
			return vodPlan{}, vodFailed(vodForeign, fmt.Errorf("publish: seq %d 는 사슬 밖 회차 %q 의 행이다", r.Seq, r.SessionID)), false
		case r.SessionID == "":
			if len(plan.rows) == 0 {
				return vodPlan{}, vodFailed(vodForeign, fmt.Errorf("publish: 사슬 범위 첫 행 seq %d 가 비귀속이다", r.Seq)), false
			}
			r = v.fillGap(r, plan.rows[len(plan.rows)-1])
			plan.marks = append(plan.marks, vodMark{seq: r.Seq, session: r.SessionID, reason: "mixed_boundary"})
		case !abandon && !r.PlaybackUploaded:
			return vodPlan{}, vodRetry(vodUnsettled, nil), false
		case !boundary.Settled(r, l.CutoffSeq):
			if !abandon {
				return vodPlan{}, vodRetry(vodUnsettled, nil), false
			}
			r = v.fillGap(r, r)
			plan.marks = append(plan.marks, vodMark{seq: r.Seq, session: r.SessionID, reason: "settle_deadline"})
		}
		plan.rows = append(plan.rows, r)
	}
	return plan, FreezeOutcome{}, true
}

// fillGap 은 행 r 을 GAP 줄로 실을 값으로 채운다 — 회차가 없으면 앞 행 prev 의 회차, PDT 가 없으면 앞 행 PDT + 길이, ③ 키가
// 없으면 키 이름(playback.SegKey — 객체는 없어도 된다 · GAP 줄은 받지 않는다)이다.
func (v *vodJob) fillGap(r, prev boundary.Row) boundary.Row {
	if r.SessionID == "" {
		r.SessionID = prev.SessionID
	}
	if r.PlaybackPDT.IsZero() {
		r.PlaybackPDT = prev.PlaybackPDT.Add(msDuration(prev.DurationMS))
	}
	if r.PlaybackS3Key == "" {
		r.PlaybackS3Key, _ = playback.SegKey(v.chain.StreamID, r.Seq) // 스트림은 vodKey 가 이미 검사했다
	}
	r.IsGap = true
	return r
}

// markGaps 는 렌더 전 원장 적기다 — 한 트랜잭션에서 사슬 꼬리 행을 잠그고, 장부를 다시 읽어 plan 을 다시 세운 뒤 GAP
// 행을 넣고 포기면 범위의 미착지 GAP 원장 행을 vod_abandoned 로 바꾼다. 돌려준 장부 · plan 이 렌더 입력이다(잠금 밖의
// 첫 읽기는 재시도 · 실패를 일찍 가르는 데만 쓴다).
//
// 다시 읽기는 잠금을 쥔 뒤에 여는 별도 스냅숏이다(index.LoadChainLedger — 잠금 앞에 커밋된 다른 writer 의 등재를 다
// 본다). 그래서 이 함수는 풀 연결 둘을 함께 쥔다. 꼬리 행을 쥔 트랜잭션이 있으면 lock_timeout 5초 뒤 재시도다.
func (v *vodJob) markGaps(ctx context.Context, abandon bool) (index.ChainLedger, vodPlan, FreezeOutcome, bool) {
	dctx, cancel := v.p.stmtCtx(ctx)
	defer cancel()
	var (
		l    index.ChainLedger
		plan vodPlan
		out  FreezeOutcome
		ok   bool
	)
	err := pgx.BeginFunc(dctx, v.p.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(dctx, vodTxnLimitsSQL); err != nil {
			return err
		}
		if _, err := tx.Exec(dctx, vodLockTailSQL, v.chain.Tail, v.chain.StreamID); err != nil {
			return err
		}
		if l, plan, out, ok = v.load(dctx, abandon); !ok || len(plan.rows) == 0 {
			return nil // 적을 것이 없다 — 커밋이 잠금만 푼다
		}
		return v.insertMarks(dctx, tx, plan)
	})
	switch {
	case index.IsLockTimeout(err):
		return index.ChainLedger{}, vodPlan{}, vodRetry(vodContended, err), false
	case err != nil:
		return index.ChainLedger{}, vodPlan{}, vodRetry(vodLedgerWrite, err), false
	}
	return l, plan, out, ok
}

// load 는 사슬 장부를 읽어 plan 을 세운다 — 장부의 사슬이 v.chain 과 다르거나 꼬리가 없으면 실패다.
func (v *vodJob) load(ctx context.Context, abandon bool) (index.ChainLedger, vodPlan, FreezeOutcome, bool) {
	l, err := index.LoadChainLedger(ctx, v.p.pool, v.chain)
	switch {
	case errors.Is(err, index.ErrChainChanged), errors.Is(err, index.ErrChainNotFound):
		return l, vodPlan{}, vodFailed(vodChainChanged, err), false
	case err != nil:
		return l, vodPlan{}, vodRetry(freezeLedger, err), false
	}
	plan, out, ok := v.plan(l, abandon)
	return l, plan, out, ok
}

// insertMarks 는 plan 의 GAP 행을 넣고, 포기면 범위의 미착지 GAP 원장 행을 vod_abandoned 로 바꾼다.
func (v *vodJob) insertMarks(ctx context.Context, tx pgx.Tx, plan vodPlan) error {
	for _, m := range plan.marks {
		if _, err := tx.Exec(ctx, vodMarkGapSQL, v.chain.StreamID, m.seq, m.session, m.reason); err != nil {
			return err
		}
	}
	if !plan.abandon {
		return nil
	}
	first, last := plan.rows[0].Seq, plan.rows[len(plan.rows)-1].Seq
	_, err := tx.Exec(ctx, vodAbandonGapsSQL, v.chain.StreamID, first, last)
	return err
}

// write 는 렌더 · 검사 · IfAbsent PUT · 확정이다.
func (v *vodJob) write(ctx context.Context, l index.ChainLedger, plan vodPlan) FreezeOutcome {
	pl := v.playlist(l, plan.rows)
	body, err := rewind.Render(pl)
	if err != nil {
		return vodFailed(freezeRender, err)
	}
	if err := rewind.Validate(pl, body, 1, nil); err != nil {
		return vodFailed(vodValidateReason(err), err)
	}
	sum := sha256.Sum256(body)
	meta := map[string]string{metaSession: v.chain.SessionIDs[0], metaBodySHA256: encodeSHA(sum)}
	pctx, cancel := context.WithTimeout(ctx, v.p.opt.PublishTimeout)
	etag, err := v.p.store.Put(pctx, v.key, IfAbsent(), body, meta)
	cancel()
	if err == nil {
		err = checkETag(etag)
	}
	if errors.Is(err, ErrPreconditionFailed) {
		etag, err = v.adopt(ctx, sum)
		if err != nil {
			return v.adoptFailed(err)
		}
	} else if err != nil {
		return vodRetry(putReason(err), err)
	}
	result := ResultFrozen
	if slices.ContainsFunc(plan.rows, func(r boundary.Row) bool { return r.IsGap }) {
		result = ResultFrozenWithGaps
	}
	return v.confirm(ctx, result, &plan, etag, sum[:])
}

// errVODConflict 는 412 뒤 Head 의 본문 sha 가 다시 렌더한 본문과 다르다는 뜻이다(D7 vod_conflict).
var errVODConflict = errors.New("publish: VOD 키에 다른 본문이 있다")

// adopt 는 412 뒤 Head 로 저장된 판을 본다 — pc-body-sha256 이 sum 과 같으면 그 판의 ETag 를 돌려준다(D7 채택).
func (v *vodJob) adopt(ctx context.Context, sum [sha256.Size]byte) (string, error) {
	hctx, cancel := context.WithTimeout(ctx, v.p.opt.PublishTimeout)
	stat, err := v.p.store.Head(hctx, v.key)
	cancel()
	if err != nil {
		return "", err
	}
	got, err := metaSHA256(stat.Meta)
	if err == nil {
		err = checkETag(stat.ETag)
	}
	switch {
	case !stat.Exists:
		return "", fmt.Errorf("publish: 412 뒤 VOD 키 %q 에 객체가 없다", v.key)
	case err != nil:
		return "", fmt.Errorf("%w — 저장된 판의 메타를 읽을 수 없다: %v", errVODConflict, err)
	case got != sum:
		return "", errVODConflict
	}
	return stat.ETag, nil
}

// adoptFailed 는 채택 실패의 결과다 — 다른 본문이면 실패(덮어쓰지 않는다), Head 를 못 읽었으면 재시도다.
func (v *vodJob) adoptFailed(err error) FreezeOutcome {
	if errors.Is(err, errVODConflict) {
		return vodFailed(vodConflict, err)
	}
	return vodRetry(freezeHeadFailed, err)
}

// playlist 는 VOD 렌더 값이다. 소유 회차는 첫 행의 회차다 — 그래야 첫 행 앞이 계승 접두가 아니고(S4) 범위 검사(S3)의
// 「회차 첫 조각부터」가 선다. 머리는 새 문서의 것이다 — TARGETDURATION 은 사슬의 최댓값(계승은 TD 를 내리지 않아 꼬리
// 값과 같다), DISC-SEQ 는 0 이다. 첫 회차의 계승은 지운다 — 그 앞 회차는 이 문서에 없어 첫 행 앞 끊김 표시가 뜻이 없다.
func (v *vodJob) playlist(l index.ChainLedger, rows []boundary.Row) rewind.Playlist {
	owner := rows[0].SessionID
	var td int32
	for _, s := range l.Sessions {
		td = max(td, s.TargetDuration)
	}
	pl := rewind.Playlist{StreamID: v.chain.StreamID, BaseURL: v.p.opt.BaseURL, Cutoff: l.CutoffSeq, Owner: owner,
		Rows: rows, Kind: rewind.KindVOD, Ended: true}
	for _, s := range l.Sessions {
		rs := renderSession(s)
		if rs.ID == owner {
			rs.InheritsSession, rs.DiscontinuityBase, rs.TargetDuration = "", 0, td
		}
		pl.Sessions = append(pl.Sessions, rs)
	}
	return pl
}

// confirm 은 사슬 전원의 VOD 확정이다 — 한 트랜잭션에서 바꾼 행 수가 사슬 수와 같을 때만 확정한다. 0행이면 다른 writer
// 가 먼저 확정했는지 본다(그러면 그 결과 · 아니면 재시도).
func (v *vodJob) confirm(ctx context.Context, result string, plan *vodPlan, etag string, sha []byte) FreezeOutcome {
	var key, etagArg any
	var first, last any
	if plan != nil {
		key, etagArg = v.key, etag
		first, last = plan.rows[0].Seq, plan.rows[len(plan.rows)-1].Seq
	}
	state := result
	if result == ResultEmpty {
		state = ResultFrozen
	}
	dctx, cancel := v.p.stmtCtx(ctx)
	defer cancel()
	var n int64
	err := pgx.BeginFunc(dctx, v.p.pool, func(tx pgx.Tx) error {
		if err := tx.QueryRow(dctx, vodConfirmSQL, v.chain.StreamID, v.chain.SessionIDs, state, key, etagArg, sha, first, last).Scan(&n); err != nil {
			return err
		}
		if n != 0 && n != int64(len(v.chain.SessionIDs)) {
			return errPartialChain
		}
		return nil
	})
	switch {
	case errors.Is(err, errPartialChain):
		return vodRetry(freezeConfirm, err)
	case err != nil:
		return vodRetry(freezeDB, err)
	case n == 0:
		return v.alreadyConfirmed(ctx)
	}
	return FreezeOutcome{Axis: AxisVOD, Result: result}
}

// errPartialChain 은 사슬 일부만 freezing 이라 확정을 되돌렸다는 뜻이다.
var errPartialChain = errors.New("publish: 사슬 일부만 VOD freezing 이다")

// alreadyConfirmed 는 확정 0행의 까닭이다 — 꼬리가 이미 VOD 종국이면 그 결과(멱등), 아니면 재시도다.
func (v *vodJob) alreadyConfirmed(ctx context.Context) FreezeOutcome {
	dctx, cancel := v.p.stmtCtx(ctx)
	defer cancel()
	var state string
	if err := v.p.pool.QueryRow(dctx, vodStateSQL, v.chain.Tail, v.chain.StreamID).Scan(&state); err != nil {
		return vodRetry(freezeDB, err)
	}
	switch state {
	case ResultFrozen, ResultFrozenWithGaps:
		return FreezeOutcome{Axis: AxisVOD, Result: state}
	case freezeStateFailed:
		return vodFailed(freezeStateFailed, nil)
	}
	return vodRetry(freezeConfirm, nil)
}

// vodValidateReason 은 VOD 검사 위반 err 의 사유다 — 본문 상한 초과는 too_large(설계 3.1 한계), 나머지는 validate 다.
func vodValidateReason(err error) string {
	if errors.Is(err, rewind.ErrBodyTooLarge) {
		return vodTooLarge
	}
	return freezeValidate
}

// vodKey 는 사슬 머리 head 의 VOD 객체 키다(K1).
//
//	vod/{streamID}/{head}/index.m3u8
//
// 성분 검사는 목록 키(manifestKey)와 같다.
func vodKey(streamID, head string) (string, error) {
	if _, err := manifestKey(streamID, head); err != nil {
		return "", err
	}
	return "vod/" + streamID + "/" + head + "/index.m3u8", nil
}

// msDuration 은 ms 정수 길이다.
func msDuration(ms int32) time.Duration { return time.Duration(ms) * time.Millisecond }

// encodeSHA 는 sha256 의 메타 표기(소문자 16진 — encodeMeta 의 pc-body-sha256 과 같다)다.
func encodeSHA(sum [sha256.Size]byte) string { return hex.EncodeToString(sum[:]) }

func vodRetry(reason string, err error) FreezeOutcome {
	return FreezeOutcome{Axis: AxisVOD, Result: ResultRetry, Retry: true, Reason: reason, Err: err}
}

func vodFailed(reason string, err error) FreezeOutcome {
	return FreezeOutcome{Axis: AxisVOD, Result: ResultFailed, Reason: reason, Err: err}
}
