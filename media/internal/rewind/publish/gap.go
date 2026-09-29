package publish

// GAP 틱 — 사다리 L2 가 고른 후보 k 를 P0 과 한 트랜잭션으로 GAP 원장에 넣고 GAP 이 섰을 때만 발행한다(설계 6.3 ·
// 계획 PR ⓒ in 줄 · 커밋 순서 4 · 체크리스트 A-3 · 판단 J17–J28). 준비 · 렌더 · 검사 · P3 · P4 는 평시 틱과
// 같다(tick.go).

import (
	"context"
	"fmt"
	"log/slog"
	"time"
)

// gapRecordSQL 은 GAP 트랜잭션의 둘째 문장 — 적격 재확인과 원장 INSERT 다(설계 4.6.3 GAP_ELIGIBLE · 6.3 · 판단 J23).
// 캐시를 믿지 않고 DB 에서 다시 본다: ① 행 ② 회차 · PDT · ③ 키 · 길이 > 0 ⑤ 컷오프 이상 ④ ③ 미확정(허위 GAP 방어) —
// ③ fence 는 같은 트랜잭션의 P0 이 세웠다. 스트림은 그 P0 이 fence 를 확인한 소유 회차($4)의 스트림(own)에 묶는다 —
// 입력 스트림($1)이 그와 어긋나면 넣지도 읽지도 않아 셋 다 거짓이다(부적격 갈래 · 보안 r1 H-1). 행을 잠그지
// 않는다(J24 — 조각 행 → 회차 행 순으로 잠그는 init 불일치 확정 문장과 거꾸로 잡으면 교착이다 · own 이 읽는 소유 회차
// 행은 P0 이 이미 잠갔다). 돌려받는 셋은 넣었나 · 문장 앞에 이미 있었나(같은 문장의 부분 질의는 CTE 의 결과를 보지
// 못한다 — PostgreSQL 17 문서 7.8.4) · DB 가 k 를 uploaded 로 봤나(행이 없으면 거짓)다.
const gapRecordSQL = `
WITH own AS (
    SELECT stream_id FROM stream_sessions WHERE session_id = $4
), ins AS (
    INSERT INTO stream_published_gaps (stream_id, seq, session_id, reason)
    SELECT s.stream_id, s.seq, s.session_id, $3
      FROM own o
      JOIN stream_segments s ON s.stream_id = o.stream_id
      JOIN stream_cutoffs c ON c.stream_id = s.stream_id
     WHERE s.stream_id = $1 AND s.seq = $2 AND s.seq >= c.cutoff_seq
       AND s.playback_upload_state IN ('pending', 'failed')
       AND s.session_id IS NOT NULL AND s.playback_pdt IS NOT NULL
       AND s.playback_s3_key IS NOT NULL AND s.duration_ms > 0
    ON CONFLICT (stream_id, seq) DO NOTHING
    RETURNING 1
)
SELECT EXISTS (SELECT 1 FROM ins),
       EXISTS (SELECT 1 FROM own o JOIN stream_published_gaps g ON g.stream_id = o.stream_id
                WHERE g.stream_id = $1 AND g.seq = $2),
       COALESCE((SELECT s.playback_upload_state = 'uploaded'
                   FROM own o JOIN stream_segments s ON s.stream_id = o.stream_id
                  WHERE s.stream_id = $1 AND s.seq = $2), false)`

// gapReasonUploadStall 은 사다리 L2 GAP 의 사유다 — 원장 reason 의 CHECK 값(설계 6.3)이자 GAP 신호의 reason 속성이다.
const gapReasonUploadStall = "upload_stall"

// GAP 트랜잭션이 끝나지 못한 자리 — insert · commit 은 gap_tx_failed 의 stage 속성 값이다(판단 J25).
const (
	gapStageBegin  = "begin"
	gapStageP0     = "p0"
	gapStageInsert = "insert"
	gapStageCommit = "commit"
)

// reserveGap 은 GAP 틱의 P0 이다 — P0 과 원장 INSERT 를 한 트랜잭션으로 보낸다(체크리스트 A-3 · 판단 J20–J26). 사유 ·
// 화해는 트랜잭션이 닫힌 뒤에만 한다(A-3 15 — 화해의 R3 · R4 는 다른 연결에서 나가 열린 트랜잭션의 행 잠금을 기다린다).
// 세대 · lease 갱신 시각은 COMMIT 성공 뒤에만 적는다(J25 — 되돌린 P0 은 없던 일이다). m0 는 BEGIN 전의 시각이다(J20).
//
//	GAP 이 섬(넣음 · 이미 있음)  결과에 seq · 렌더로 간다 — 새로 넣었으면 rewind_gap_published_total WARN(J28)
//	출처 대조 불일치            P0 만 커밋 — etag_source_mismatch + 화해(J22)
//	DB 가 uploaded 로 봄        결과에 seq · PUT 없이 끝 / 부적격 — PUT 없이 끝(J18 · J26)
//
// GAP 이 서지 않은 틱은 올리지 않는다 — 창은 k 를 settled 로 본 겹침 창이라 GAP 없이 올리면 다음 평시 목록의 MSN 이
// 역행한다(J26).
func (t *tick) reserveGap(ctx context.Context, m0 time.Time, prev *Manifest, seq int64) (reservation, bool) {
	res, v, fail := t.gapTx(ctx, prev, seq)
	if fail != nil {
		t.gapFailed(ctx, fail)
		return reservation{}, false
	}
	st := &t.out.State
	st.Gen, st.FenceHeld, st.RenewedAt = res.gen, true, m0
	switch {
	case v.mismatch:
		t.abandon(ctx, reasonETagSourceMismatch)
		t.reconcile(ctx)
	case v.inserted || v.existed:
		t.out.GapSeq = &seq
		if v.inserted {
			t.p.log.Log(ctx, slog.LevelWarn, gapPublishedLog,
				"stream", t.stream, "session", t.session, "seq", seq, "reason", gapReasonUploadStall)
		}
		return res, true
	case v.uploaded:
		t.out.UploadedSeq = &seq
	}
	return reservation{}, false
}

// gapVerdict 는 GAP 트랜잭션이 본 것이다 — mismatch 는 출처 대조 불일치로 원장 문장을 보내지 않았다는 뜻이고(결정 8 ·
// 판단 J22) 나머지 셋은 gapRecordSQL 의 값(넣었나 · 이미 있었나 · DB 가 uploaded 로 봤나)이다.
type gapVerdict struct{ mismatch, inserted, existed, uploaded bool }

// gapFailure 는 GAP 트랜잭션이 끝나지 못한 자리와 그 오류다 — 트랜잭션은 이미 닫혔다(되돌렸거나 COMMIT 이 닫았다).
type gapFailure struct {
	stage string // gapStageBegin · gapStageP0 · gapStageInsert · gapStageCommit
	err   error
}

// gapTx 는 GAP 트랜잭션이다(체크리스트 A-3 3–7) — BEGIN · P0(첫 문장) · 출처 대조 · 적격 재확인과 원장 INSERT · COMMIT.
// 문장 시한은 트랜잭션 하나에 stmtCtx 하나다(판단 J20). 실패하면 되돌리고 돌아간다 — 되돌림 ctx 는 되돌리는 순간에 루프
// ctx 에서 취소를 떼고 같은 문장 시한을 씌워 만든다(끝난 ctx 로 되돌리면 pgx 가 연결을 닫는다 — 미리 만들어 두면 문장
// 시한을 다 쓴 갈래에서 그 ctx 도 끝나 있다). COMMIT 오류 뒤에는 되돌리지 않는다 — pgx 의 Commit 은 결과와 상관없이
// 트랜잭션을 닫는다(미뤄 둔 되돌림은 ErrTxClosed 로 끝난다 · A-3 15).
func (t *tick) gapTx(ctx context.Context, prev *Manifest, seq int64) (reservation, gapVerdict, *gapFailure) {
	dctx, cancel := t.p.stmtCtx(ctx)
	defer cancel()
	tx, err := t.p.pool.Begin(dctx)
	if err != nil {
		return reservation{}, gapVerdict{}, &gapFailure{stage: gapStageBegin, err: err}
	}
	defer func() {
		rctx, rcancel := t.p.stmtCtx(context.WithoutCancel(ctx))
		defer rcancel()
		_ = tx.Rollback(rctx)
	}()

	res, err := scanReservation(tx.QueryRow(dctx, p0ReserveSQL,
		t.session, t.p.opt.Writer, t.out.State.Gen, t.p.opt.Lease))
	if err != nil {
		return reservation{}, gapVerdict{}, &gapFailure{stage: gapStageP0, err: err}
	}
	v := gapVerdict{mismatch: !sameSource(res.etag, prev)}
	if !v.mismatch {
		row := tx.QueryRow(dctx, gapRecordSQL, t.stream, seq, gapReasonUploadStall, t.session)
		if err := row.Scan(&v.inserted, &v.existed, &v.uploaded); err != nil {
			return reservation{}, gapVerdict{}, &gapFailure{stage: gapStageInsert, err: err}
		}
	}
	if err := tx.Commit(dctx); err != nil {
		return reservation{}, gapVerdict{}, &gapFailure{stage: gapStageCommit, err: err}
	}
	return res, v, nil
}

// gapFailed 는 GAP 트랜잭션이 끝나지 못한 갈래를 적고 처치한다(판단 J25) — 트랜잭션이 닫힌 뒤라 화해해도 된다.
// 부른 쪽 ctx 가 끝나 실패했으면 아무것도 남기지 않는다(stopped).
//
//	begin   P0 전이라 어느 사유에도 들지 않는다 — Outcome.Err(로그 · 화해 없음 · 커밋 3 관례)
//	p0      p0_no_row · p0_unknown(p0Failed — 커밋 3 그대로)
//	insert  gap_tx_failed(stage=insert) — 되돌렸다(적용 없음) + 화해
//	commit  gap_tx_failed(stage=commit) — 적용 여부 모름: 요구 적재 + 화해(서버가 커밋한 GAP 행을 캐시가 모르면 평시
//	        틱이 k 를 일반 줄로 올린 뒤 적재가 그 줄을 GAP 으로 바꾼다 — 적재가 그 행을 먼저 싣게 한다)
func (t *tick) gapFailed(ctx context.Context, f *gapFailure) {
	switch f.stage {
	case gapStageBegin:
		t.out.Err = fmt.Errorf("publish: GAP 트랜잭션 시작 session=%q: %w", t.session, f.err)
	case gapStageP0:
		t.p0Failed(ctx, f.err)
	default:
		if t.stopped(ctx) {
			return
		}
		t.abandon(ctx, reasonGapTxFailed, "stage", f.stage, "err", f.err.Error())
		t.out.DemandLoad = f.stage == gapStageCommit
		t.reconcile(ctx)
	}
}
