package publish

// DVR 동결과 봉인 — M6 설계 2.1 발행 층의 FreezeDVR(D17 4.5.3 F0–F4) · Endlist(D17 4.6.4 E0–E5)다(POK-196 M6-2 커밋 ①).
//
// 둘은 같은 준비를 거친다 — fence 획득 → Head 로 직전 판 P(R1 · R2) → DB 를 그 판에 맞춤(R3 · R4) → terminal 예약
// (reserveTerminalSQL). 예약이 세대를 하나 올리므로, 예약 전에 P0 을 마친 평시 틱은 P4 가 0행이 되고 그 틱의 PUT 은
// If-Match 로 막힌다(D3 — 닫힌 판 뒤에 열린 판이 닿지 않는다). 예약은 dvr_state 를 freezing 으로 두어 뒤 P0 도 막는다.
//
// 한 번 부르면 한 번 시도한다. 결과는 FreezeOutcome 값이고 로그는 남기지 않는다 — 시도 횟수(4초 × 3)와 다음 단계
// (2단 Endlist · 3단 freeze_failed)는 판정부(M6-3b finalize)가 세고 그 신호(session_freeze · session_freeze_retry ·
// session_freeze_failed — 설계 8절)도 판정부가 남긴다. 회차 state(live · ending)는 읽지도 쓰지도 않는다 — ending
// 전이는 M6 종료 문장의 몫이다.

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"slices"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// 동결 축과 결과 — FreezeOutcome 의 Axis · Result 값이다(설계 2.1).
const (
	AxisDVR = "dvr"
	AxisVOD = "vod"

	ResultFrozen         = "frozen"           // 문서를 닫고 장부에 확정했다
	ResultFrozenWithGaps = "frozen_with_gaps" // VOD 에 GAP 줄이 있다
	ResultEmpty          = "empty"            // 실을 행이 없어 문서 없이 닫았다(빈 동결)
	ResultRetry          = "retry"            // 이번 시도는 서지 않았다 — 같은 걸음을 다시 한다(Retry 참)
	ResultFailed         = "failed"           // 이 경로로는 닫을 수 없다 — 판정부가 다음 단계로 간다
)

// FreezeOutcome 은 동결 · 봉인 한 번의 결과다(설계 2.1).
//
// Retry 가 참이면 같은 걸음을 4초 뒤 다시 한다(상한 3회는 판정부가 센다). Result 가 failed 면 같은 입력으로 다시 해도
// 서지 않는다 — DVR 은 봉인(Endlist)으로, 봉인도 실패면 freeze_failed 로 간다. Reason 은 retry · failed 의 사유(로그
// 속성 reason 값)다. Err 는 사유에 딸린 원인 오류다(없을 수 있다).
type FreezeOutcome struct {
	Axis   string
	Result string
	Retry  bool
	Reason string
	Err    error
}

// 재시도 · 실패 사유 — FreezeOutcome.Reason 값이다.
const (
	freezeFence        = "fence"               // fence 를 얻지 못했거나 잃었다
	freezeDB           = "db"                  // DB 문장 실패(결과 모름 포함)
	freezeHeadFailed   = "head_failed"         // Head 실패
	freezeGetFailed    = "get_failed"          // Get 실패
	freezeBodyChanged  = "body_changed"        // Get 본문이 Head 메타의 sha 와 다르다(그 사이 판이 바뀜)
	freezeMetaInvalid  = "meta_invalid"        // Head 메타 · ETag 해석 실패
	freezeLedger       = "ledger_failed"       // 장부 읽기 실패
	freezeLedgerCapped = "ledger_capped"       // 장부 적재가 행 수 상한에 닿았다
	freezePrevMismatch = "prev_mismatch"       // 장부에서 다시 뽑은 P 의 행이 P 의 자기기술과 다르다
	freezeEmptyAfter   = "empty_after_publish" // 발행본이 있는데 실을 행이 없다
	freezeRender       = "render"              // 렌더할 수 없는 입력
	freezeValidate     = "validate"            // 발행 전 검사(S1–S7) 위반
	freezeMetaRange    = "meta_out_of_range"   // 올릴 판의 메타 정수가 위끝을 넘는다
	freezeSeal         = "seal"                // 봉인할 수 없는 본문
	freezePut412       = "put_412"             // PUT 조건 불일치
	freezePut404       = "put_404"             // If-Match 대상 없음
	freezePut409       = "put_409"             // 동시 요청 충돌
	freezePutUnknown   = "put_unknown"         // PUT 결과 모름(네트워크 · 5xx · 받을 수 없는 ETag)
	freezeConfirm      = "confirm_no_row"      // 확정 CAS 0행
	freezeStateFailed  = "freeze_failed"       // 장부가 이미 freeze_failed 다
)

// 장부 적재 범위 — 캐시 적재의 기본값과 같다(rewind/cache Options — 되짚기 두 시간 · 행 수 1만).
const (
	freezeLookback = 2 * time.Duration(boundary.WindowMS) * time.Millisecond
	freezeRowCap   = 10000
)

// reserveTerminalSQL 은 F0 · E0 terminal 예약이다(D17 4.5.3 F0 · 4.6.4 E0) — fence 를 쥐었고 아직 닫히지 않았을 때만
// dvr_state 를 freezing 으로 두고(시작 시각은 첫 예약의 값 — 좌초 판정의 기준) 세대를 하나 올린다. 재진입(이미
// freezing)도 받는다. state 는 쓰지 않는다. 돌려받는 세대가 닫는 판의 pc-gen 과 F4 · E5 의 세대 조건이다.
const reserveTerminalSQL = `
UPDATE stream_sessions
   SET dvr_state        = 'freezing',
       dvr_freezing_at  = COALESCE(dvr_freezing_at, pg_catalog.now()),
       manifest_gen     = manifest_gen + 1,
       fence_expires_at = pg_catalog.now() + $3::interval
 WHERE session_id = $1 AND stream_id = $4 AND writer_fence = $2
   AND fence_expires_at > pg_catalog.now()
   AND dvr_state IN ('open', 'freezing')
RETURNING manifest_gen`

// dvrStateSQL 은 예약이 0행일 때 까닭을 가르는 읽기다 — 이미 닫혔는가(frozen · freeze_failed).
const dvrStateSQL = `SELECT dvr_state FROM stream_sessions WHERE session_id = $1 AND stream_id = $2`

// confirmFrozenSQL 은 F4 · E5 확정 CAS 다 — P4 와 같은 열(manifest_etag · discontinuity_base · published_seq)에 동결 열을
// 더해 fence · 세대 조건으로 한 문장에 쓴다(설계 4.1 R1 5). 닫힌 판 범위 [$9 MSN, $6 끝 seq] 의 GAP 원장 recorded 행도
// 확정한다(p4ConfirmSQL 과 같은 꼴 · 원자). 결과는 CAS 가 바꾼 행 수다.
const confirmFrozenSQL = `
WITH f4 AS (
    UPDATE stream_sessions
       SET manifest_etag = $4, discontinuity_base = $5, published_seq = $6,
           dvr_state = 'frozen', dvr_s3_key = $7, dvr_etag = $4, dvr_sha256 = $8,
           dvr_first_seq = $9, dvr_last_seq = $6, dvr_disc_base = $5, dvr_frozen_at = pg_catalog.now()
     WHERE session_id = $1 AND writer_fence = $2 AND manifest_gen = $3
       AND fence_expires_at > pg_catalog.now() AND dvr_state = 'freezing'
    RETURNING stream_id
), confirmed AS (
    UPDATE stream_published_gaps g
       SET published_state = 'put_confirmed', put_confirmed_at = pg_catalog.now()
      FROM f4
     WHERE g.stream_id = f4.stream_id AND g.seq BETWEEN $9 AND $6
       AND g.published_state = 'recorded'
)
SELECT pg_catalog.count(*) FROM f4`

// confirmEmptySQL 은 빈 동결 확정이다 — 발행본이 없을 때(manifest_etag NULL — 준비의 R4 가 저장소에 맞췄다)만 문서 키 없이
// 닫는다.
const confirmEmptySQL = `
UPDATE stream_sessions
   SET dvr_state = 'frozen', dvr_frozen_at = pg_catalog.now()
 WHERE session_id = $1 AND writer_fence = $2 AND manifest_gen = $3
   AND fence_expires_at > pg_catalog.now() AND dvr_state = 'freezing' AND manifest_etag IS NULL`

// FreezeDVR 는 회차의 되감기 목록을 닫는 정상 경로다(D17 4.5.3 F0–F4).
// 렌더 입력은 장부에서 한 번 읽는다(LoadSessionLedger) — 캐시를 읽지 않는다.
// 소유 회차는 그 세션 자신이다. TARGETDURATION 은 개시 때 한 번 쓴 target_duration 이고,
// MSN · DISC-SEQ 는 평시 틱과 같은 산식이다(4.1 R1 — 축출이 있으면 오른다).
// Kind 는 KindSliding 이다. 렌더 행이 0이면 PUT 없이 빈 동결로 확정한다.
//
// 이미 닫힌 판(앞 시도가 PUT 뒤 확정 전에 멈춤)이면 다시 쓰지 않는다 — 준비의 R3 가 그 판으로 동결을 확정하고(terminal
// 승격) 예약이 0행이라 멱등 성공이다. 닫힌 목록은 바꾸지 않는다.
func (p *Publisher) FreezeDVR(ctx context.Context, streamID, sessionID string) FreezeOutcome {
	f, out, ok := p.beginFreeze(ctx, streamID, sessionID)
	if !ok {
		return out
	}
	l, err := index.LoadSessionLedger(ctx, p.pool, streamID, sessionID, f.bounds())
	switch {
	case err != nil:
		return dvrRetry(freezeLedger, err)
	case l.Capped:
		return dvrFailed(freezeLedgerCapped, nil)
	}
	pl, ok := ledgerPlaylist(l, sessionID, f.prev)
	if !ok {
		return dvrFailed(freezeRender, fmt.Errorf("publish: 소유 회차 %q 가 장부에 없다", sessionID))
	}
	pl.StreamID = streamID
	if len(pl.Rows) == 0 {
		if f.prev != nil {
			return dvrFailed(freezeEmptyAfter, nil)
		}
		return f.confirmEmpty(ctx)
	}
	return f.freezeRendered(ctx, l, pl)
}

// freezeRendered 는 F1 의 DISC-SEQ 부터 F4 까지다 — 목록 pl 을 닫아 렌더 · 검사하고 올린 뒤 확정한다.
func (f *freezeJob) freezeRendered(ctx context.Context, l index.RewindLedger, pl rewind.Playlist) FreezeOutcome {
	disc, out, ok := f.discontinuitySequence(l, pl)
	if !ok {
		return out
	}
	pl.BaseURL, pl.Ended = f.p.opt.BaseURL, true
	pl.Sessions = slices.Clone(pl.Sessions)
	for i := range pl.Sessions {
		if pl.Sessions[i].ID == pl.Owner {
			pl.Sessions[i].DiscontinuityBase = disc
		}
	}
	body, err := rewind.Render(pl)
	if err != nil {
		return dvrFailed(freezeRender, err)
	}
	var prevDesc *rewind.Published
	if f.prev != nil {
		prevDesc = &f.prev.Published
	}
	if err := rewind.Validate(pl, body, f.gen, prevDesc); err != nil {
		return dvrFailed(freezeValidate, err)
	}
	desc := describe(pl, f.gen, sha256.Sum256(body))
	if err := checkMetaRange(desc, pl); err != nil {
		return dvrFailed(freezeMetaRange, err)
	}
	return f.putAndConfirm(ctx, body, desc)
}

// discontinuitySequence 는 닫는 판의 DISC-SEQ 다 — P 가 없으면 소유 회차의 개시 base, 있으면 절대식이다(평시 틱과 같다 —
// P 의 행은 장부에서 다시 뽑는다). P 의 행이 자기기술과 다르면 재시도다(설계 4.1 R1 3 — 3회면 2단 봉인).
func (f *freezeJob) discontinuitySequence(l index.RewindLedger, pl rewind.Playlist) (int64, FreezeOutcome, bool) {
	if f.prev == nil {
		owner, _ := sessionOf(pl, pl.Owner)
		return owner.DiscontinuityBase, FreezeOutcome{}, true
	}
	prevRows := ownerRows(l, pl.Owner, f.prev.MediaSequence, f.prev.PublishedSeq)
	d, err := NextDiscontinuitySequence(f.prev.Published, prevRows, pl.Rows[0].Seq)
	if err != nil {
		return 0, dvrRetry(freezePrevMismatch, err), false
	}
	return d, FreezeOutcome{}, true
}

// Endlist 는 정체 마감(L2′) · 동결 2단 · 좌초 회수의 봉인 경로다(D17 4.6.4 E0–E5).
// 마지막 발행 본문 끝에 SealEndlist 한 줄만 더해 If-Match 로 쓴다. 머리 줄은 바꾸지 않는다.
//
// 본문은 Get 으로 읽고 Head 메타의 pc-body-sha256 과 대조한다(E1 — 발행자는 상태를 들지 않아 마지막 발행 본문을 메모리에
// 두지 않는다). 발행본이 없으면 봉인할 것이 없어 빈 동결이다. 이미 닫힌 판이면 준비의 R3 승격이
// 확정해 다시 쓰지 않는다(E2). 본문만 닫히고 메타가 열린 판이면 같은 본문에 닫힘 메타를 맞춘다.
func (p *Publisher) Endlist(ctx context.Context, streamID, sessionID string) FreezeOutcome {
	f, out, ok := p.beginFreeze(ctx, streamID, sessionID)
	if !ok {
		return out
	}
	if f.prev == nil {
		return f.confirmEmpty(ctx)
	}
	gctx, cancel := context.WithTimeout(ctx, p.opt.PublishTimeout)
	body, err := p.store.Get(gctx, f.key)
	cancel()
	switch {
	case err != nil:
		return dvrRetry(freezeGetFailed, err)
	case sha256.Sum256(body) != f.prev.BodySHA256:
		return dvrRetry(freezeBodyChanged, nil)
	}
	sealed, err := rewind.SealEndlist(body)
	switch {
	case errors.Is(err, rewind.ErrAlreadySealed):
		sealed = body // 본문은 닫혔는데 메타가 열려 있다 — 같은 본문에 닫힘 메타를 맞춘다
	case err != nil:
		return dvrFailed(freezeSeal, err)
	}
	desc := f.prev.Published
	desc.Gen, desc.Terminal, desc.BodySHA256 = f.gen, true, sha256.Sum256(sealed)
	if !withinMetaLimit(desc.Gen) {
		return dvrFailed(freezeMetaRange, fmt.Errorf("publish: 봉인 판 세대 %d 가 2^53-1 을 넘는다", desc.Gen))
	}
	return f.putAndConfirm(ctx, sealed, desc)
}

// freezeJob 은 동결 · 봉인 한 번의 문맥이다 — 준비(beginFreeze)가 채운다.
type freezeJob struct {
	p       *Publisher
	stream  string
	session string
	key     string
	// prev 는 Head 로 읽은 직전 판 P 다. nil 이면 발행본이 없다.
	prev *Manifest
	// gen 은 terminal 예약이 돌려준 세대다.
	gen int64
}

// beginFreeze 는 동결 · 봉인의 준비다 — fence 획득 → Head(R1 · R2) → DB 화해(R3 · R4) → terminal 예약(F0 · E0). 거짓이면
// 돌려받은 결과로 끝낸다. 이미 닫힌 회차면 그 상태의 결과다(frozen 은 멱등 성공 · freeze_failed 는 실패).
func (p *Publisher) beginFreeze(ctx context.Context, streamID, sessionID string) (*freezeJob, FreezeOutcome, bool) {
	key, err := manifestKey(streamID, sessionID)
	if err != nil {
		return nil, dvrFailed(freezeRender, err), false
	}
	f := &freezeJob{p: p, stream: streamID, session: sessionID, key: key}
	if ok, err := p.acquire(ctx, sessionID); err != nil || !ok {
		return nil, dvrRetry(freezeFence, err), false
	}
	if out, ok := f.reconcile(ctx); !ok {
		return nil, out, false
	}
	var out FreezeOutcome
	if !f.reserve(ctx, &out) {
		return nil, out, false
	}
	return f, FreezeOutcome{}, true
}

// reconcile 은 R1 · R2 · R3 · R4 다 — Head 로 P 를 읽고 fence 를 쥔 채 DB 세대 · ETag 를 그 판에 맞춘다(닫힌 판이면 R3
// 가 동결까지 확정한다). DB 세대가
// 저장된 판의 pc-gen 보다 낮을 수 있어(P4 결과를 잃음) 예약 전에 맞춘다 — 아니면 닫는 판이 S6(세대 단조)에 걸린다.
func (f *freezeJob) reconcile(ctx context.Context) (FreezeOutcome, bool) {
	hctx, cancel := context.WithTimeout(ctx, f.p.opt.PublishTimeout)
	stat, err := f.p.store.Head(hctx, f.key)
	cancel()
	if err != nil {
		return dvrRetry(freezeHeadFailed, err), false
	}
	sql, args := r4ClearETagSQL, []any{f.session, f.p.opt.Writer}
	if stat.Exists {
		desc, err := parseMeta(stat.Meta, f.session)
		if err == nil {
			err = checkETag(stat.ETag)
		}
		if err != nil {
			return dvrFailed(freezeMetaInvalid, err), false
		}
		f.prev = &Manifest{Published: desc, ETag: stat.ETag}
		sql, args = r3ReconcileSQL, r3Args(f.session, f.p.opt.Writer, f.key, desc, stat.ETag)
	}
	var gen int64
	if err := f.queryRow(ctx, sql, args...).Scan(&gen); err != nil {
		return dbOutcome(err), false
	}
	return FreezeOutcome{}, true
}

// reserve 는 F0 · E0 다. 0행이면 까닭을 가른다 — 이미 닫혔으면 그 상태의 결과, 아니면 fence 를 잃었다(재시도).
func (f *freezeJob) reserve(ctx context.Context, out *FreezeOutcome) bool {
	err := f.queryRow(ctx, reserveTerminalSQL, f.session, f.p.opt.Writer, f.p.opt.Lease, f.stream).Scan(&f.gen)
	if err == nil {
		return true
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		*out = dbOutcome(err)
		return false
	}
	var state string
	if err := f.queryRow(ctx, dvrStateSQL, f.session, f.stream).Scan(&state); err != nil {
		*out = dbOutcome(err)
		return false
	}
	switch state {
	case "frozen":
		*out = FreezeOutcome{Axis: AxisDVR, Result: ResultFrozen}
	case "freeze_failed":
		*out = dvrFailed(freezeStateFailed, nil)
	default:
		*out = dvrRetry(freezeFence, nil)
	}
	return false
}

// putAndConfirm 은 F3 · F4(E4 · E5)다 — 닫는 판 body 를 조건부로 올리고(P 가 있으면 IfMatch(P.ETag), 없으면 IfAbsent) 그
// 판으로 확정한다. PUT 한 번의 마감은 T_pub 이다. 실패는 재시도다(판을 잃지 않는다 — D6).
func (f *freezeJob) putAndConfirm(ctx context.Context, body []byte, desc rewind.Published) FreezeOutcome {
	cond := IfAbsent()
	if f.prev != nil {
		cond = IfMatch(f.prev.ETag)
	}
	pctx, cancel := context.WithTimeout(ctx, f.p.opt.PublishTimeout)
	etag, err := f.p.store.Put(pctx, f.key, cond, body, encodeMeta(desc, f.session))
	cancel()
	if err == nil {
		err = checkETag(etag)
	}
	if err != nil {
		return dvrRetry(putReason(err), err)
	}
	return f.confirm(ctx, desc, etag)
}

// putReason 은 PUT 실패 err 의 사유다(RC-19 분기 — 설계 4.4.5).
func putReason(err error) string {
	switch {
	case errors.Is(err, ErrPreconditionFailed):
		return freezePut412
	case errors.Is(err, ErrNotFound):
		return freezePut404
	case errors.Is(err, ErrConflict):
		return freezePut409
	}
	return freezePutUnknown
}

// confirm 은 F4 · E5 다 — 판 desc(ETag etag)로 장부를 확정한다.
func (f *freezeJob) confirm(ctx context.Context, desc rewind.Published, etag string) FreezeOutcome {
	var n int64
	err := f.queryRow(ctx, confirmFrozenSQL, f.session, f.p.opt.Writer, f.gen, etag, desc.DiscontinuitySequence,
		desc.PublishedSeq, f.key, desc.BodySHA256[:], desc.MediaSequence).Scan(&n)
	switch {
	case err != nil:
		return dbOutcome(err)
	case n != 1:
		return dvrRetry(freezeConfirm, nil)
	}
	return FreezeOutcome{Axis: AxisDVR, Result: ResultFrozen}
}

// confirmEmpty 는 빈 동결 확정이다.
func (f *freezeJob) confirmEmpty(ctx context.Context) FreezeOutcome {
	dctx, cancel := f.p.stmtCtx(ctx)
	defer cancel()
	tag, err := f.p.pool.Exec(dctx, confirmEmptySQL, f.session, f.p.opt.Writer, f.gen)
	switch {
	case err != nil:
		return dbOutcome(err)
	case tag.RowsAffected() != 1:
		return dvrRetry(freezeConfirm, nil)
	}
	return FreezeOutcome{Axis: AxisDVR, Result: ResultEmpty}
}

// bounds 는 장부 적재 범위다 — P 가 있으면 하한을 P.MSN 까지 내린다(P 의 행을 다시 뽑아 DISC-SEQ 를 센다).
func (f *freezeJob) bounds() index.LedgerBounds {
	b := index.LedgerBounds{Lookback: freezeLookback, RowCap: freezeRowCap}
	if f.prev != nil {
		hint := f.prev.MediaSequence
		b.Hint = &hint
	}
	return b
}

// queryRow 는 DB 문장 하나를 문장 시한 안에서 보낸다. 시한 ctx 는 Scan 이 끝난 뒤 풀린다(stmtRow).
func (f *freezeJob) queryRow(ctx context.Context, sql string, args ...any) stmtRow {
	dctx, cancel := f.p.stmtCtx(ctx)
	return stmtRow{row: f.p.pool.QueryRow(dctx, sql, args...), cancel: cancel}
}

// stmtRow 는 문장 시한을 Scan 까지 쥐는 pgx.Row 다.
type stmtRow struct {
	row    pgx.Row
	cancel context.CancelFunc
}

func (r stmtRow) Scan(dest ...any) error {
	defer r.cancel()
	return r.row.Scan(dest...)
}

// dbOutcome 은 DB 문장 실패 err 의 결과다 — 0행이면 fence 를 잃었고 아니면 결과 모름이다. 둘 다 재시도다.
func dbOutcome(err error) FreezeOutcome {
	if errors.Is(err, pgx.ErrNoRows) {
		return dvrRetry(freezeFence, nil)
	}
	return dvrRetry(freezeDB, err)
}

func dvrRetry(reason string, err error) FreezeOutcome {
	return FreezeOutcome{Axis: AxisDVR, Result: ResultRetry, Retry: true, Reason: reason, Err: err}
}

func dvrFailed(reason string, err error) FreezeOutcome {
	return FreezeOutcome{Axis: AxisDVR, Result: ResultFailed, Reason: reason, Err: err}
}

// ledgerPlaylist 는 장부 l 로 만든 소유 회차 owner 의 목록 값이다 — 평시 틱의 창 · 거름과 같다.
//
//	창    boundary.Compute(장부, prevTail = P.MSN) — P 가 없으면 컷오프부터
//	거름  소유 회차의 행 + 소유 회차가 계승 회차면 계승한 직전 회차의 행(cache.Playlist 와 같은 1단계)
//	하한  P 가 있으면 P.MSN 아래 행은 싣지 않는다
//
// 하한이 평시 틱과 같은 목록인 까닭: 창 꼬리가 P.MSN 앞으로 계산돼도(P.MSN 부터의 길이가 1시간에 못 미치면 Compute 는
// 컷오프를 꼬리로 준다) 직전 꼬리와 P.MSN 사이의 소유 · 접두 행은 앞 판들이 이미 축출했다 — P.MSN 이 직전 창의 첫 소유
// · 접두 행이기 때문이다. 이 하한이 없으면 축출된 접두가 되살아나 MSN 이 뒤로 간다(S2).
//
// 소유 회차가 장부 회차 목록에 없으면 거짓이다(행이 없으면 회차도 실리지 않는다 — 그때는 빈 목록 · 참).
func ledgerPlaylist(l index.RewindLedger, owner string, prev *Manifest) (rewind.Playlist, bool) {
	pl := rewind.Playlist{Cutoff: l.CutoffSeq, Owner: owner}
	if !l.HasCutoff || len(l.Rows) == 0 {
		return pl, true
	}
	var prevTail int64
	if prev != nil {
		prevTail = prev.MediaSequence
	}
	w, _ := boundary.Compute(ledgerSnapshot{l}, prevTail)
	from := w.TailSeq
	if prev != nil {
		from = max(from, prev.MediaSequence)
	}
	return selectOwner(l, owner, from, w.HeadSeq)
}

// ownerRows 는 장부 l 의 [from, to] 에서 소유 회차 owner 의 목록 거름을 통과한 행이다(P 의 행 다시 뽑기).
func ownerRows(l index.RewindLedger, owner string, from, to int64) rewind.Playlist {
	pl, _ := selectOwner(l, owner, from, to)
	return pl
}

// selectOwner 는 장부 l 의 [from, to] 행에 소유 회차 거름을 건 목록 값이다(StreamID · BaseURL 은 비운다 — 부르는 쪽이
// 채운다). 소유 회차를 모르면 거짓이다.
func selectOwner(l index.RewindLedger, owner string, from, to int64) (rewind.Playlist, bool) {
	pl := rewind.Playlist{Cutoff: l.CutoffSeq, Owner: owner}
	sessions := map[string]rewind.Session{}
	for _, s := range l.Sessions {
		sessions[s.SessionID] = renderSession(s)
	}
	o, ok := sessions[owner]
	if !ok {
		return pl, false
	}
	hasPrefix := false
	for _, r := range l.Rows {
		if r.Seq < from || r.Seq > to {
			continue
		}
		switch {
		case r.SessionID == owner:
		case o.InheritsSession != "" && r.SessionID == o.InheritsSession:
			hasPrefix = true
		default:
			continue
		}
		pl.Rows = append(pl.Rows, boundaryRow(r))
	}
	if hasPrefix {
		ps, ok := sessions[o.InheritsSession]
		if !ok {
			return pl, false
		}
		pl.Sessions = append(pl.Sessions, ps)
	}
	pl.Sessions = append(pl.Sessions, o)
	return pl, true
}

// sessionOf 는 목록 pl 의 회차 id 다.
func sessionOf(pl rewind.Playlist, id string) (rewind.Session, bool) {
	for _, s := range pl.Sessions {
		if s.ID == id {
			return s, true
		}
	}
	return rewind.Session{}, false
}

// renderSession 은 장부 회차 s 의 렌더 값이다(cache.Session.forRender 와 같은 열).
func renderSession(s index.RewindSession) rewind.Session {
	return rewind.Session{
		ID: s.SessionID, InheritsSession: s.InheritsSession, DiscontinuityBase: s.DiscontinuityBase,
		TargetDuration: s.TargetDuration, MinSeq: s.MinSeq, InitUploaded: s.InitUploaded,
	}
}

// boundaryRow 는 장부 행 r 의 경계 행이다(열이 1:1 이다).
func boundaryRow(r index.RewindRow) boundary.Row {
	return boundary.Row{
		Seq: r.Seq, SessionID: r.SessionID, DurationMS: r.DurationMS, PlaybackPDT: r.PlaybackPDT,
		PlaybackS3Key: r.PlaybackS3Key, PlaybackUploaded: r.PlaybackUploaded, IsGap: r.IsGap,
	}
}

// ledgerSnapshot 은 장부 적재분을 boundary.Snapshot 으로 내보인다(rewind/cache 의 snapshot 과 같은 형).
type ledgerSnapshot struct{ l index.RewindLedger }

func (s ledgerSnapshot) Cutoff() (int64, bool) { return s.l.CutoffSeq, s.l.HasCutoff }

func (s ledgerSnapshot) RowsFrom(from int64) []boundary.Row {
	var rows []boundary.Row
	for _, r := range s.l.Rows {
		if r.Seq >= from {
			rows = append(rows, boundaryRow(r))
		}
	}
	return rows
}
