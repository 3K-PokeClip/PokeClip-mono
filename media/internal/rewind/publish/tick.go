package publish

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/boundary"
)

// p0ReserveSQL 은 P0 세대 예약이다(설계 4.4.3 P0 · 계획 4.5 A0 결정 3 · A2 결정 9). fence 검증과 lease 갱신을
// 겸한다(설계 6.2 주 갱신). 설계의 다섯 조건에 둘을 더했다 — state = 'live'(ending 회차는 예약을 받지 않는다)와
// init_uploaded_at IS NOT NULL(G5 하드 가드). 다섯 조건 가운데 lease 조건은 형이 설계와 다르다 — 설계는
// 남은 lease 2초를 요구하지만(4.4.3 P0) 여기서는 lease 가 끝나지 않았는지만 본다(계획 부기 41). 이 문장이
// lease 를 now() + Lease 로 새로 쓰므로 갱신 전에 남은 시간은 지키는 것이 없다.
// base 는 건드리지 않는다(A1 결정 2 — P4 · R3 가 쓴다). 돌려받는 것은
// 틱이 쓰는 둘 — 새 세대와 manifest_etag(첫 PUT 전인가 · P3 의 최초 · 갱신 갈래 · 출처 대조)다. 설계 RETURNING
// 의 dvr_state 는 WHERE 가 'open' 으로 고정하고 published_seq 는 M4 틱이 쓰지 않는다.
const p0ReserveSQL = `
UPDATE stream_sessions
   SET manifest_gen     = manifest_gen + 1,
       fence_expires_at = now() + $4::interval
 WHERE session_id = $1 AND writer_fence = $2
   AND fence_expires_at > now()
   AND manifest_gen = $3 AND dvr_state = 'open'
   AND state = 'live' AND init_uploaded_at IS NOT NULL
RETURNING manifest_gen, manifest_etag`

// p2RevokeSQL 은 P2′ 계승 취소다(계획 4.5 A2 결정 2 의 문장 그대로). 첫 PUT 전(manifest_etag IS NULL) · live 에서, 이
// 세대를 예약한 writer 만 취소한다. 돌려받은 base(0)가 취소 뒤 목록의 DISC-SEQ 다(A1 결정 6).
const p2RevokeSQL = `
UPDATE stream_sessions
   SET inherits_session = NULL, discontinuity_base = 0
 WHERE session_id = $1 AND writer_fence = $2 AND manifest_gen = $3
   AND fence_expires_at > now()
   AND state = 'live' AND manifest_etag IS NULL AND inherits_session IS NOT NULL
RETURNING discontinuity_base`

// p4ConfirmSQL 은 P4 확정 CAS 다(설계 4.4.3 P4 · 계획 4.5 A1 결정 2 · 8). fence · 세대 조건으로 올린 판의 ETag 와
// DISC-SEQ(base — 파생값) · 마지막 seq 를 쓴다. state 조건은 넣지 않는다 — 예약 뒤 ending 된 틱도 확정한다(A0
// 결정 2 · 체크리스트 A-2 12). published_seq 는 R3 가 max 로 맞추는 열이라 같은 값을 쓴다.
const p4ConfirmSQL = `
UPDATE stream_sessions
   SET manifest_etag = $4, discontinuity_base = $5, published_seq = $6
 WHERE session_id = $1 AND writer_fence = $2 AND manifest_gen = $3
   AND fence_expires_at > now()`

// TickInput 은 발행 한 틱의 렌더 입력이다 — 루프가 캐시로 만든 값이다(워커는 캐시를 만지지 않는다 — 계획 4.5
// A3 결정 1).
type TickInput struct {
	// Playlist 는 소유 회차 목록의 행과 회차다(cache.Playlist 가 돌려준 새 값 — 창은 루프가 정했다). BaseURL 은
	// 발행자가 채우고, 소유 회차의 base 자리에는 아래 DISC-SEQ 를 적는다(캐시 base 는 파생값이라 쓰지 않는다 —
	// 결정 2).
	Playlist rewind.Playlist
	// DiscontinuitySequence 는 이 목록의 DISC-SEQ 다 — P 가 있으면 NextDiscontinuitySequence 의 절대식 값(결정
	// 1), 없으면 소유 회차의 개시 base(결정 6).
	DiscontinuitySequence int64
}

// NextDiscontinuitySequence 는 P 뒤에 낼 목록의 EXT-X-DISCONTINUITY-SEQUENCE 다 — 계획 4.5 A1 결정 1 의
// 절대식이다.
//
//	D_new = D_P + EvictedDiscontinuityTags(P 의 행, MSN_new)
//
// 기준이 그 URL 에 실제로 저장된 판 P 라서 PUT 실패 · P0 뒤 사망 · 재시도가 증분을 두 번 세지 않는다(A1 「멱등」).
// prevRows 는 루프가 캐시에서 다시 뽑은 P 의 행이다(cache.Playlist(stream, owner, Window{P.MSN,
// P.PublishedSeq}) — 결정 4). 세기 전에 그 행이 P 의 자기기술과 맞는지 본다.
//
//	첫 행 seq = pc-msn · 행 수 = pc-seg-count · 끝 행 seq = pc-pub-seq
//
// 하나라도 어긋나면 세지 않고 *PrevMismatchError 를 돌려준다 — 루프가 발행을 멈추고 그 스트림에 적재를
// 요구한다(힌트 P.MSN — A3 결정 6 넷째 부류). 확인되지 않은 행으로 덜 세는 일을 없앤다. 부르는 자리 · 적재
// 요구 · prev_mismatch 로그는 루프 몫이다(커밋 7 — 판단 J2).
//
// P 가 없으면(최초 생성 경로) 이 함수를 부르지 않는다 — 그때 DISC-SEQ 는 소유 회차의 개시 base 다(결정 6).
func NextDiscontinuitySequence(prev rewind.Published, prevRows rewind.Playlist, nextMSN int64) (int64, error) {
	rows := prevRows.Rows
	switch {
	case len(rows) == 0:
		return 0, &PrevMismatchError{LoadHint: prev.MediaSequence, Reason: "P 의 행이 없다"}
	case rows[0].Seq != prev.MediaSequence:
		return 0, &PrevMismatchError{LoadHint: prev.MediaSequence,
			Reason: fmt.Sprintf("첫 행 seq %d ≠ pc-msn %d", rows[0].Seq, prev.MediaSequence)}
	case len(rows) != prev.SegmentCount:
		return 0, &PrevMismatchError{LoadHint: prev.MediaSequence,
			Reason: fmt.Sprintf("행 수 %d ≠ pc-seg-count %d", len(rows), prev.SegmentCount)}
	case rows[len(rows)-1].Seq != prev.PublishedSeq:
		return 0, &PrevMismatchError{LoadHint: prev.MediaSequence,
			Reason: fmt.Sprintf("끝 행 seq %d ≠ pc-pub-seq %d", rows[len(rows)-1].Seq, prev.PublishedSeq)}
	}
	evicted, err := rewind.EvictedDiscontinuityTags(prevRows, nextMSN)
	if err != nil {
		return 0, err
	}
	return prev.DiscontinuitySequence + evicted, nil
}

// PrevMismatchError 는 캐시에서 다시 뽑은 P 의 행이 P 의 자기기술과 맞지 않는다는 결과다(계획 4.5 A1 결정 4) —
// 발행을 멈추고 LoadHint 로 그 스트림에 적재를 요구하라는 뜻이다.
type PrevMismatchError struct {
	// LoadHint 는 적재 하한 힌트다 — P.MSN(A3 결정 6 넷째 부류).
	LoadHint int64
	// Reason 은 어긋난 곳이다 — 사람이 읽는 진단이다.
	Reason string
}

func (e *PrevMismatchError) Error() string {
	return fmt.Sprintf("publish: P 재구성 불일치(적재 힌트 %d) — %s", e.LoadHint, e.Reason)
}

// Tick 은 발행 한 틱이다 — 설계 4.4.3 의 P0–P4 를 계획 4.5 A0 · A1 · A2 가 고친 차례로 돈다(체크리스트 A-2).
//
//	준비   fence 가 없으면 얻고 곧바로 화해한다 · 화해가 예약돼 있으면 화해부터 — P 가 바뀌었으면 여기서 끝낸다
//	       (루프가 준 DISC-SEQ 는 옛 P 로 센 값이다)
//	P0     m0 를 잡고 세대 예약 — 0행이면 포기 + 화해(p0_no_row), DB 오류면 같은 처치(p0_unknown — 결과 모름)
//	대조   P0 의 manifest_etag ≠ P.ETag 면 PUT 없이 포기 + 화해(etag_source_mismatch — 결정 8)
//	P1     렌더 — 세션 필터 뒤 행이 0 이면 끝(빈 창 — 판단 J5)
//	P2     검사 S1–S7(prev = P) — S4 는 첫 PUT 전이면 P2′ 로 계승을 취소하고 다시 렌더 · 검사, 뒤면 발행 중단
//	       (s4_after_publish) · 나머지 위반은 발행 중단(validate · disc_seq_decrease)
//	생략   새 본문이 P 와 같으면 끝(예약 세대는 버린다 — 결정 7)
//	마감   지금이 m0 + lease − T_pub 이후면 PUT 없이 포기 · 화해 예약(publish_deadline — 결정 9)
//	범위   올릴 판의 메타 정수나 끝 조각 DSN 이 읽는 쪽 위끝(2^53−1)을 넘으면 PUT 없이 발행 중단(meta_out_of_range — checkMetaRange)
//	P3     조건부 PUT + 메타 8키 — 마감 min(P3 시작 + T_pub, m0 + lease − T_pub) · RC-19 분기(put)
//	P4     확정 CAS — 0행이면 발행 불성립 + 화해(p4_no_row), DB 오류면 같은 처치(p4_unknown — 결과 모름)
//
// 결과 상태는 루프가 그대로 보관한다(틱 도중 회차 state 를 다시 보지 않는다 — 예약된 틱은 ending 이 와도 P4 까지
// 간다). 중단 · 포기는 rewind_publish_aborted 로 남긴다(같은 사유는 한 번). 표 밖의 실패는 Outcome.Err 로 돌려주고
// 로그하지 않는다. ctx 는 루프 수명 ctx 다(Lanes.Start 의 계약) — 그것이 끝났으면 실패 갈래는 로그 · 화해 없이
// Outcome.Err(그 ctx 오류)로 끝낸다(stopped).
func (p *Publisher) Tick(ctx context.Context, st State, in TickInput) Outcome {
	key, err := manifestKey(in.Playlist.StreamID, in.Playlist.Owner)
	if err != nil {
		return Outcome{State: st, Err: err}
	}
	t := &tick{p: p, stream: in.Playlist.StreamID, session: in.Playlist.Owner, key: key, out: Outcome{State: st}}
	if t.ready(ctx) {
		t.publish(ctx, in)
	}
	return t.finish()
}

// tick 은 발행 작업 한 번의 문맥이다 — 결과(out)를 고쳐 가며 들고 다닌다.
type tick struct {
	p       *Publisher
	stream  string
	session string
	key     string
	out     Outcome
	// aborted 는 이번 작업에 중단 · 포기가 있었나다 — 없으면 끝에서 로그 가드를 푼다(상태가 바뀌었다).
	aborted bool
}

// ready 는 P0 앞 준비다 — fence 가 없으면 얻고(0행이면 다른 writer 차례 — 로그 없음), 얻었거나 화해가 예약돼
// 있으면 화해한다(설계 6.2 · 계획 4.5 A1 결정 5). 화해가 P 를 바꿨으면 거짓이다 — 루프가 준 DISC-SEQ 는 옛 P
// 로 센 값이다.
func (t *tick) ready(ctx context.Context) bool {
	st := &t.out.State
	if !st.FenceHeld {
		sent := t.p.now()
		ok, err := t.p.acquire(ctx, t.session)
		if err != nil {
			t.out.Err = err
			return false
		}
		if !ok {
			return false
		}
		st.FenceHeld, st.RenewedAt, st.ReconcileDue = true, sent, true
	}
	if !st.ReconcileDue {
		return true
	}
	before := st.Prev
	return t.reconcile(ctx) && samePrev(before, st.Prev)
}

// publish 는 P0 부터 P4 까지다.
func (t *tick) publish(ctx context.Context, in TickInput) {
	prev := t.out.State.Prev
	m0 := t.p.now()
	res, ok := t.reserve(ctx)
	if !ok {
		return
	}
	t.out.State.RenewedAt = m0
	if !sameSource(res.etag, prev) {
		t.abandon(ctx, reasonETagSourceMismatch)
		t.reconcile(ctx)
		return
	}
	pl := t.playlist(in)
	if len(pl.Rows) == 0 {
		return
	}
	pl, body, ok := t.check(ctx, pl, res)
	if !ok {
		return
	}
	sum := sha256.Sum256(body)
	if prev != nil && sum == prev.BodySHA256 {
		return // 같은 본문 — 예약한 세대는 버린다(결정 7)
	}
	t3, latest := t.p.now(), m0.Add(t.p.opt.Lease-t.p.opt.PublishTimeout)
	if !t3.Before(latest) {
		t.abandon(ctx, reasonPublishDeadline)
		t.out.State.ReconcileDue = true
		return
	}
	desc := describe(pl, res.gen, sum)
	if err := checkMetaRange(desc, pl); err != nil {
		t.halt(ctx, reasonMetaOutOfRange, "err", err.Error())
		return
	}
	etag, ok := t.put(ctx, body, desc, prev, earlier(t3.Add(t.p.opt.PublishTimeout), latest))
	if !ok {
		return
	}
	t.confirm(ctx, res.gen, desc, etag)
}

// earlier 는 두 시각 가운데 이른 쪽이다 — P3 마감 = min(P3 시작 + T_pub, m0 + lease − T_pub)(결정 9).
func earlier(a, b time.Time) time.Time {
	if b.Before(a) {
		return b
	}
	return a
}

// sameSource 는 P0 이 돌려준 DB manifest_etag 와 루프의 P 가 같은 판을 가리키는가다(결정 8) — 둘 다 없거나(첫 PUT
// 전) 같은 ETag 다. 도달 가능한 상태에서는 늘 같다: 발행에 성공하면 P4 가 같은 ETag 를 쓰고, 화해는 fence 를 쥔
// 채 두 쪽을 같은 Head 값으로 맞춘다. 이 대조는 G8 증명(E4)이 기대는 그 동일성을 코드로 단언한다.
func sameSource(dbETag *string, prev *Manifest) bool {
	if dbETag == nil || prev == nil {
		return dbETag == nil && prev == nil
	}
	return *dbETag == prev.ETag
}

// reservation 은 P0 이 돌려준 값이다.
type reservation struct {
	// gen 은 이 틱이 예약한 세대다 — 메타 pc-gen · S6 · P4 의 세대 조건이다.
	gen int64
	// etag 는 DB manifest_etag 다. nil 이면 그 목록의 첫 PUT 전이다.
	etag *string
}

// scanReservation 은 P0 결과 행을 읽는다. 행은 풀의 한 문장이든 트랜잭션 안의 문장이든 같다(판단 J7 — GAP 틱의
// P0 은 커밋 4 에서 트랜잭션 안에서 같은 문장 · 같은 해석을 쓴다). 0행이면 pgx.ErrNoRows 다.
func scanReservation(row pgx.Row) (reservation, error) {
	var r reservation
	err := row.Scan(&r.gen, &r.etag)
	return r, err
}

// reserve 는 P0 이다. 0행(CAS 거부 — p0_no_row)이거나 DB 오류(결과 모름 — p0_unknown)면 틱을 포기하고 화해한다 —
// 서버가 예약을 커밋했는데 결과를 잃었어도 화해의 R3 가 DB 세대를 다시 읽어 온다.
func (t *tick) reserve(ctx context.Context) (reservation, bool) {
	dctx, cancel := t.p.stmtCtx(ctx)
	defer cancel()
	st := &t.out.State
	res, err := scanReservation(t.p.pool.QueryRow(dctx, p0ReserveSQL,
		t.session, t.p.opt.Writer, st.Gen, t.p.opt.Lease))
	if err == nil {
		st.Gen, st.FenceHeld = res.gen, true
		return res, true
	}
	if t.stopped(ctx) {
		return reservation{}, false
	}
	if errors.Is(err, pgx.ErrNoRows) {
		t.abandon(ctx, reasonP0NoRow)
	} else {
		t.abandon(ctx, reasonP0Unknown, "err", err.Error())
	}
	t.reconcile(ctx)
	return reservation{}, false
}

// playlist 는 렌더할 목록 값이다 — 루프가 준 값에 BaseURL 을 채우고 소유 회차 base 자리에 DISC-SEQ 를 적는다.
// 루프의 값은 바꾸지 않는다(회차 목록을 새로 만든다).
func (t *tick) playlist(in TickInput) rewind.Playlist {
	pl := in.Playlist
	pl.BaseURL = t.p.opt.BaseURL
	pl.Sessions = slices.Clone(pl.Sessions)
	for i := range pl.Sessions {
		if pl.Sessions[i].ID == pl.Owner {
			pl.Sessions[i].DiscontinuityBase = in.DiscontinuitySequence
		}
	}
	return pl
}

// check 는 P1 렌더와 P2 검사다(체크리스트 A-2 4–6). 올릴 목록과 본문을 돌려준다. 거짓이면 이 틱은 올리지 않는다.
//
// S4(계승 접두) 위반의 처치는 첫 PUT 전인가로 갈린다(A2 결정 1–3). 첫 PUT 전(P0 의 manifest_etag 가 NULL)이면
// P2′ 로 계승을 영속 취소하고 접두를 뺀 목록을 다시 렌더 · 검사한다 — 남은 행이 0 이면 올리지 않는다. 첫 PUT
// 뒤면 계승을 유지한 채 발행을 멈춘다. 판정 입력은 루프가 준 목록 값 하나다(S4 의 모든 조항이 같은 값을
// 본다) — DB 를 다시 읽지 않는다.
func (t *tick) check(ctx context.Context, pl rewind.Playlist, res reservation) (rewind.Playlist, []byte, bool) {
	body, verr, err := t.renderChecked(pl, res.gen)
	if err == nil && errors.Is(verr, rewind.ErrRevokeInheritance) {
		if res.etag != nil {
			t.halt(ctx, reasonS4AfterPublish, "check", "S4", "err", verr.Error())
			return pl, nil, false
		}
		base, ok := t.revoke(ctx, res.gen)
		if !ok {
			return pl, nil, false
		}
		if pl = withoutPrefix(pl, base); len(pl.Rows) == 0 {
			return pl, nil, false
		}
		body, verr, err = t.renderChecked(pl, res.gen)
	}
	switch {
	case err != nil:
		t.out.Err = err
		return pl, nil, false
	case verr != nil:
		t.haltInvalid(ctx, verr)
		return pl, nil, false
	}
	return pl, body, true
}

// renderChecked 는 목록 pl 을 렌더해 P2 검사(prev = P)에 넣는다. err 는 렌더할 수 없는 입력(입력 결함)이고, verr 는
// 검사 위반이다.
func (t *tick) renderChecked(pl rewind.Playlist, gen int64) (body []byte, verr, err error) {
	body, err = rewind.Render(pl)
	if err != nil {
		return nil, nil, err
	}
	return body, rewind.Validate(pl, body, gen, t.prevDesc()), nil
}

// haltInvalid 는 S4 를 뺀 검사 위반으로 발행을 멈춘다(ERROR) — S2 의 DISC-SEQ 조항은 disc_seq_decrease, 나머지는
// validate(속성 check)다(A2 결정 4 · 로그 표).
func (t *tick) haltInvalid(ctx context.Context, verr error) {
	reason, check := reasonValidate, ""
	if errors.Is(verr, rewind.ErrDiscontinuitySequenceDecreased) {
		reason = reasonDiscSeqDecrease
	}
	var v *rewind.Violation
	if errors.As(verr, &v) {
		check = v.Check
	}
	t.halt(ctx, reason, "check", check, "err", verr.Error())
}

// revoke 는 P2′ 다. 1행이면 계승을 취소했다고 적고(루프가 캐시에 반영 — 커밋 5 · 7) 돌려받은 base 를 준다. 0행이거나
// 결과를 모르면(모든 오류를 모름으로 — 서버 커밋 여부를 모른다) 틱을 포기하고 화해한 뒤 요구 적재를 낸다
// (revoke_unknown · A3 결정 6 넷째 부류, 힌트 없음) — 서버가 취소를 커밋했는데 캐시가 계승을 든 채면, 적재가 DB
// 값(해제됨 · base 0)을 싣기 전까지 틱이 없다. 부른 쪽 ctx 가 끝나 실패했으면 아무것도 남기지 않는다(stopped —
// 다음 프로세스는 부팅 적재로 DB 값을 싣는다).
func (t *tick) revoke(ctx context.Context, gen int64) (int64, bool) {
	dctx, cancel := t.p.stmtCtx(ctx)
	defer cancel()
	var base int64
	if err := t.p.pool.QueryRow(dctx, p2RevokeSQL, t.session, t.p.opt.Writer, gen).Scan(&base); err != nil {
		if t.stopped(ctx) {
			return 0, false
		}
		t.abandon(ctx, reasonRevokeUnknown, errAttrs(err)...)
		t.out.DemandLoad = true
		t.reconcile(ctx)
		return 0, false
	}
	t.out.Revoked = true
	return base, true
}

// withoutPrefix 는 계승을 취소한 목록이다 — 소유 회차 앞의 다른 회차 행(접두)을 빼고, 소유 회차의 계승을 지우고 base
// 를 P2′ 가 돌려준 값으로 둔다(A2 결정 2 · A1 결정 6 — 취소됐으면 0).
func withoutPrefix(pl rewind.Playlist, base int64) rewind.Playlist {
	var rows []boundary.Row
	for _, r := range pl.Rows {
		if r.SessionID == pl.Owner {
			rows = append(rows, r)
		}
	}
	var sessions []rewind.Session
	for _, s := range pl.Sessions {
		if s.ID == pl.Owner {
			s.InheritsSession, s.DiscontinuityBase = "", base
			sessions = append(sessions, s)
		}
	}
	pl.Rows, pl.Sessions = rows, sessions
	return pl
}

// prevDesc 는 P2 검사의 prev 다 — P 의 자기기술이고 P 가 없으면 nil 이다.
func (t *tick) prevDesc() *rewind.Published {
	if p := t.out.State.Prev; p != nil {
		return &p.Published
	}
	return nil
}

// put 은 P3 조건부 PUT 과 RC-19 분기다(설계 4.4.5 · 계획 4.5 A1 결정 8–10 · J14). 첫 판은 IfAbsent, 갱신은
// IfMatch(P.ETag) 다(G8 전제 (가)). 메타 8키를 같은 요청에 싣는다. 재시도는 409 한 번뿐이고 deadline(결정 9 의 P3
// 마감) 안에서만 돈다 — If-None-Match 409 는 곧바로, If-Match 409 는 Head 로 ETag 를 다시 봐서 P.ETag 그대로일
// 때만 다시 보낸다. 성공하면 새 판의 ETag 를 돌려준다 — 받을 수 없는 ETag(checkETag)면 결과 모름으로 다룬다(쓰기는
// 적용됐을 수 있다 — 화해의 Head 가 확인한다). 실패 갈래는 putFailed 가 적는다.
func (t *tick) put(ctx context.Context, body []byte, desc rewind.Published, prev *Manifest, deadline time.Time) (string, bool) {
	pctx, cancel := context.WithDeadline(ctx, deadline)
	defer cancel()
	cond, meta := IfAbsent(), encodeMeta(desc, t.session)
	if prev != nil {
		cond = IfMatch(prev.ETag)
	}
	etag, err := t.p.store.Put(pctx, t.key, cond, body, meta)
	if errors.Is(err, ErrConflict) {
		if prev != nil && !t.stillCurrent(ctx, pctx, prev) {
			return "", false
		}
		etag, err = t.p.store.Put(pctx, t.key, cond, body, meta)
	}
	if err == nil {
		err = checkETag(etag)
	}
	if err != nil {
		t.putFailed(ctx, err, prev)
		return "", false
	}
	return etag, true
}

// stillCurrent 는 If-Match 409 뒤 같은 P3 마감(pctx) 안에서 Head 로 ETag 를 다시 본다(결정 9 · J14). 저장된 판이
// P 그대로면 참이다(한 번 다시 보낸다). 아니면 갈래의 사유를 남기고 거짓이다.
//
//	ETag 다름   update_412 — 결정 10 경로: 이 Head 로 P 를 저장된 판으로 바꾸고 포기
//	객체 없음   update_404 — 이 Head 로 R4(다음 틱이 최초 생성 경로 — J15)
//	Head 실패   head_failed — 포기 · 화해 예약(R1 과 같은 이름 — 운영자가 볼 원인이 같다). 부른 쪽 ctx 가 끝나 실패했으면
//	           아무것도 남기지 않는다(stopped)
func (t *tick) stillCurrent(ctx, pctx context.Context, prev *Manifest) bool {
	stat, err := t.p.store.Head(pctx, t.key)
	switch {
	case err != nil:
		if t.stopped(ctx) {
			return false
		}
		t.halt(ctx, reasonHeadFailed, "err", err.Error())
		t.out.State.ReconcileDue = true
		return false
	case !stat.Exists:
		t.abandon(ctx, reasonUpdate404)
		t.reconcileStat(ctx, stat)
		return false
	case stat.ETag != prev.ETag:
		t.abandon(ctx, reasonUpdate412)
		t.reconcileStat(ctx, stat)
		return false
	}
	return true
}

// putFailed 는 PUT 이 실패한 갈래를 적고 필요한 화해를 한다(설계 4.4.5 RC-19 · 계획 4.5 A1 로그 표).
//
//	412   첫 판 → create_412 · 갱신 → update_412(재시도 없이 포기 — 결정 10). 화해가 P 를 저장된 판으로 바꾼다
//	404   갱신 → update_404. 화해의 R4 가 P 와 DB manifest_etag 를 비운다(다음 틱이 최초 생성 경로 — J15)
//	409   다시 보내도 409 → put_conflict. 적용되지 않았으니 화해하지 않는다
//	그 밖  결과 모름(네트워크 · 5xx · 보낸 뒤 ctx 만료 · ETag 없는 200 · 받을 수 없는 ETag) → put_unknown. 화해(Head)로
//	      확인한다
//
// 부른 쪽 ctx 가 끝났으면 어느 갈래도 적지 않는다(stopped).
func (t *tick) putFailed(ctx context.Context, err error, prev *Manifest) {
	if t.stopped(ctx) {
		return
	}
	attrs := []any{"err", err.Error()}
	switch {
	case errors.Is(err, ErrPreconditionFailed) && prev == nil:
		t.abandon(ctx, reasonCreate412, attrs...)
	case errors.Is(err, ErrPreconditionFailed):
		t.abandon(ctx, reasonUpdate412, attrs...)
	case errors.Is(err, ErrNotFound) && prev != nil:
		t.abandon(ctx, reasonUpdate404, attrs...)
	case errors.Is(err, ErrConflict):
		t.abandon(ctx, reasonPutConflict, attrs...)
		return
	default:
		t.abandon(ctx, reasonPutUnknown, attrs...)
	}
	t.reconcile(ctx)
}

// confirm 은 P4 다. 0행(CAS 거부 — p4_no_row)이거나 DB 오류(결과 모름 — p4_unknown)면 발행이 서지 않은 것이라
// 곧바로 화해한다 — 올린 판은 저장소에 있으므로 화해가 그 판을 P 로 삼는다.
func (t *tick) confirm(ctx context.Context, gen int64, desc rewind.Published, etag string) {
	dctx, cancel := t.p.stmtCtx(ctx)
	defer cancel()
	tag, err := t.p.pool.Exec(dctx, p4ConfirmSQL, t.session, t.p.opt.Writer, gen, etag, desc.DiscontinuitySequence, desc.PublishedSeq)
	if err == nil && tag.RowsAffected() == 1 {
		t.out.State.Prev = &Manifest{Published: desc, ETag: etag}
		t.out.Published = true
		return
	}
	if t.stopped(ctx) {
		return
	}
	if err != nil {
		t.abandon(ctx, reasonP4Unknown, "err", err.Error())
	} else {
		t.abandon(ctx, reasonP4NoRow)
	}
	t.reconcile(ctx)
}

// describe 는 목록 pl 을 올릴 판의 자기기술이다 — 메타 8키가 이 값을 싣는다. DISC-SEQ 는 소유 회차 base
// 자리에 적은 값이다. M4 목록은 닫지 않는다(ENDLIST 는 M6 — 계획 4.5 A0 결정 1).
func describe(pl rewind.Playlist, gen int64, sum [sha256.Size]byte) rewind.Published {
	d := rewind.Published{
		Gen:           gen,
		MediaSequence: pl.Rows[0].Seq,
		PublishedSeq:  pl.Rows[len(pl.Rows)-1].Seq,
		SegmentCount:  len(pl.Rows),
		BodySHA256:    sum,
	}
	for _, s := range pl.Sessions {
		if s.ID == pl.Owner {
			d.DiscontinuitySequence = s.DiscontinuityBase
		}
	}
	return d
}

// stopped 는 부른 쪽 ctx(루프 수명 — Lanes.Start 의 계약)가 끝나 이 작업을 멈추는가다. 참이면 그 까닭을
// Outcome.Err 에 적었다. 실패 갈래는 로그 · 화해 · 화해 예약 · 요구 적재보다 먼저 이것을 본다 — 루프가 끝나는 중에
// ctx 가 끊은 실패는 저장소 · DB 의 상태를 말하지 않는다(거짓 head_failed · 결과 모름을 남기지 않는다). 다음
// 프로세스는 fence 를 얻자마자 화해한다(설계 6.2 · 체크리스트 A-4 1).
func (t *tick) stopped(ctx context.Context) bool {
	err := ctx.Err()
	if err != nil {
		t.out.Err = err
	}
	return err != nil
}

// abandon 은 틱만 포기하는 갈래를 적는다(WARN). 같은 사유가 이어지면 처음 한 번만 남긴다.
func (t *tick) abandon(ctx context.Context, reason string, attrs ...any) {
	t.note(ctx, slog.LevelWarn, reason, attrs)
}

// halt 는 발행이 서는 갈래를 적는다(ERROR). 같은 사유가 이어지면 처음 한 번만 남긴다.
func (t *tick) halt(ctx context.Context, reason string, attrs ...any) {
	t.note(ctx, slog.LevelError, reason, attrs)
}

// note 는 중단 · 포기 한 건을 적는다. 오류는 문장에 섞지 않고 속성(err)으로만 싣는다(커밋 2 리뷰 인계 — S3
// 오류 문자열에는 서버 <Message> 의 개행이 실린다). err 속성 값은 clipErr 로 자른다 — 모든 사유와 모든 오류(저장소 ·
// pgx · 검사 위반)가 이 한 자리를 지난다.
func (t *tick) note(ctx context.Context, level slog.Level, reason string, attrs []any) {
	t.aborted = true
	if !t.out.State.noteAbort(reason) {
		return
	}
	args := append([]any{"stream", t.stream, "session", t.session, "reason", reason}, attrs...)
	for i := len(args) - len(attrs); i+1 < len(args); i += 2 { // attrs 의 키 · 값 쌍
		if s, ok := args[i+1].(string); ok && args[i] == "err" {
			args[i+1] = clipErr(s)
		}
	}
	t.p.log.Log(ctx, level, abortedLog, args...)
}

// clipErr 는 로그 err 속성 값 s 가 1024바이트를 넘으면 앞 1024바이트(UTF-8 글자 가운데서는 자르지 않는다)에 잘림과
// 원래 길이를 붙인다(보안 r5 L1). 저장소 오류 문자열에는 외부 값(x-amz-request-id · x-amz-id-2 헤더 · 오류 본문
// <Message>)이 길이 제한 없이 실린다. 1024바이트는 우리가 내는 오류(저장소 · pgx · 검사 위반 · 메타 해석 — 길어야 수백
// 바이트)를 온전히 싣고, 보통의 오류면 다른 속성과 합친 로그 한 줄이 syslog 수신 쪽에 권고된 크기(RFC 5424 6.1 —
// 2048옥텟) 안에 든다 — 발행 정지를 알리는 ERROR 가 수집기의 줄 길이 상한에 잘리거나 버려지지 않는다.
func clipErr(s string) string {
	n := 1024
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return fmt.Sprintf("%s…(잘림 — 원래 %d바이트)", s[:n], len(s))
}

// finish 는 작업을 닫는다 — 중단 · 포기 없이 끝났으면 로그 가드를 푼다.
func (t *tick) finish() Outcome {
	if !t.aborted {
		t.out.State.clearAbort()
	}
	return t.out
}

// errAttrs 는 문장 결과 err 의 로그 속성이다 — 0행(pgx.ErrNoRows)이거나 오류가 없으면 싣지 않는다.
func errAttrs(err error) []any {
	if err == nil || errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	return []any{"err", err.Error()}
}

// samePrev 는 두 P 가 같은 판인가다(둘 다 없음 포함).
func samePrev(a, b *Manifest) bool {
	if a == nil || b == nil {
		return a == b
	}
	return *a == *b
}
