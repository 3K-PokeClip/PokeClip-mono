package index

// 정산 스캔 · 빠른 길의 판독(POK-196 M6-3b 커밋 ① — 설계 M6 2.2 정산 스캔의 실행 가능 조건 · 커서 · 종결 정정
// EndingSession). 두 길(30초 정산 스캔 · 종료 직후 빠른 길)이 같은 판독식(endingRowSelectSQL)으로 행을 채운다 — 판정
// (rewind/finalize.Next)이 보는 칸이 길마다 달라지면 D72(꺼짐 빈 동결)가 다시 열린다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// SettleWindow 는 정산 창이다(설계 3.1 phase 2 — 꼬리 ending_at 부터 15분). 창이 지나면 ③ 미착지 조각을 기다리지 않고
// GAP 으로 포기하며(FreezeVOD abandon), init 미확정 세션도 init 판정이 끝난 것으로 본다(InitSettled).
const SettleWindow = 15 * time.Minute

// FreezeOrphanAfter 는 좌초 판정 시각이다(D17 4.1(d)) — dvr_state='freezing' 이 이만큼 지나면 동결하던 writer 가 죽은
// 것으로 보고 좌초 회수(RecoverOrphan)로 거둔다. 그 전에는 다른 writer 가 동결 중이라 스캔이 고르지 않는다.
const FreezeOrphanAfter = 60 * time.Second

// chainCloseReasons 는 창을 기다리지 않고 사슬을 닫는 꼬리 사유다(설계 3.1 사슬 닫힘) — 재접속이 그 사슬을 이을 수
// 없는 종료다. 스캔 WHERE(ANY($n)) · EndingSession.ChainCloseDue · CloseChain 재확인이 이 한 벌을 쓴다.
var chainCloseReasons = []string{"intentional", "init_mismatch", "td_exceeded"}

// CloseOptions 는 닫힘 시한과 신호다. 사슬 · 회차는 마지막 조각 끝에서 Window + Slack 이 지나면 닫힌다(재접속 창
// 300초 + 닫힘 여유 60초 — K8).
type CloseOptions struct {
	Window time.Duration // 재접속 창(cfg.ReconnectWindow)
	Slack  time.Duration // 닫힘 여유(60초)
	// Log 는 session_chain_closed · session_chain_recheck_failed · session_ended · topology_lock_timeout 을 받는다.
	// nil 이면 slog.Default() 다.
	Log *slog.Logger
}

// closeAfter 는 마지막 조각 끝에서 닫힘까지의 시간이다.
func (o CloseOptions) closeAfter() time.Duration { return o.Window + o.Slack }

// EndingSession 은 정산 스캔 한 행이자 빠른 길의 입력 행이다. Now 는 그 문장의 pg_catalog.now() 다(판정 시계 하나).
type EndingSession struct {
	SessionID, StreamID, RoundID, EndReason, DVRState, VODState, InheritsSession string
	// LastEnd 는 그 세션 마지막 조각의 끝이다(조각이 없으면 ending_at). DVRFreezingAt · StopMarkedAt 은 NULL 이면 영값이다.
	EndingAt, DVRFreezingAt, LastEnd, StopMarkedAt, Now time.Time
	// HasSuccessor 는 같은 회차의 후속 계승이 있는가다(사슬 간선 규칙 — chainEdgeSQL).
	HasSuccessor bool
	// HasPublishTrace 는 그 세션에 발행 흔적이 있는가다 — manifest_etag IS NOT NULL ∨ 그 세션 조각 가운데
	// playback_upload_state='uploaded' 가 있음. 꺼짐(S3_BUCKET 빈 값)에서는 이 칸이 거짓일 때만 빈 동결을 낸다(D72).
	HasPublishTrace bool
	// InitSettled 는 그 세션의 init 판정이 끝났는가다 — init_uploaded_at IS NOT NULL ∨ now ≥ ending_at + 정산 창.
	// 사슬 닫힘은 사슬 전원이 참일 때만 간다(D75 — CloseChain 이 잠금 뒤 구성원 전원을 다시 본다).
	InitSettled bool
}

// ChainCloseDue 는 이 꼬리의 사슬을 지금 닫을 때인가다 — 즉시 닫는 사유 ∨ 멈춘 시각 표식 ∨ 마지막 조각 끝에서
// closeAfter 가 지났다. 후속 · init 판정 · dvr 종국은 따로 본다.
func (s EndingSession) ChainCloseDue(closeAfter time.Duration) bool {
	return slices.Contains(chainCloseReasons, s.EndReason) || !s.StopMarkedAt.IsZero() || s.Now.Sub(s.LastEnd) > closeAfter
}

// ScanCursor 는 정산 스캔의 순회 자리다(루프 메모리). 영값이면 처음부터다.
type ScanCursor struct {
	EndingAt  time.Time
	SessionID string
}

// endingRowSelectSQL 은 판독식이다($1 정산 창). 별칭 p 가 그 세션 행이고, 후속 판정은 사슬 간선 상수를 그대로 끼운다
// (c = 후속 · p = 이 행 — chainEdgeSQL 의 별칭 그대로). 뒤에 WHERE 를 붙여 쓴다.
//
// LastEnd 와 ③ 흔적은 LIMIT 1 LATERAL 로 그 세션 조각만 본다(LastEnd 는 stream_segments_session_idx 의 마지막 seq 한
// 행 · ③ 흔적은 부분 인덱스 stream_segments_uploaded_session_idx 의 uploaded 한 행). EXISTS 로 쓰면 플래너가 OR 갈래의 EXISTS 를 해시 부분 계획(조각 표 순차 스캔)으로 바꿀 수 있다(D26 실측).
// 조각이 없으면 LastEnd 는 ending_at 이다. duration_ms 는 registry previousSessionSQL 과 같은 까닭으로 ::double precision
// 으로 맞춘다. now 는 pg_catalog 로 한정하고 COALESCE 는 구문이라 한정하지 않는다.
const endingRowSelectSQL = `
SELECT p.session_id, p.stream_id, COALESCE(p.round_id, '') AS round_id, COALESCE(p.end_reason, '') AS end_reason,
       p.dvr_state, p.vod_state, COALESCE(p.inherits_session, '') AS inherits_session,
       p.ending_at, p.dvr_freezing_at, COALESCE(le.last_end, p.ending_at) AS last_end, p.stop_marked_at,
       pg_catalog.now() AS now,
       EXISTS (SELECT 1 FROM stream_sessions c WHERE ` + chainEdgeSQL + `) AS has_successor,
       (p.manifest_etag IS NOT NULL OR tr.uploaded IS NOT NULL) AS has_trace,
       (p.init_uploaded_at IS NOT NULL OR pg_catalog.now() >= p.ending_at + $1::interval) AS init_settled
  FROM stream_sessions p
  LEFT JOIN LATERAL (
       SELECT g.start_wall_utc + g.duration_ms::double precision * interval '1 millisecond' AS last_end
         FROM stream_segments g
        WHERE g.stream_id = p.stream_id AND g.session_id = p.session_id
        ORDER BY g.seq DESC LIMIT 1) le ON true
  LEFT JOIN LATERAL (
       SELECT true AS uploaded
         FROM stream_segments g
        WHERE g.stream_id = p.stream_id AND g.session_id = p.session_id AND g.playback_upload_state = 'uploaded'
        LIMIT 1) tr ON true`

// listEndingSessionsSQL 은 정산 스캔이다 — state='ending' 부분 인덱스(stream_sessions_ending_idx) 위에서 커서 뒤 행 가운데
// 지금 다음 걸음이 있는 행만 (ending_at, session_id) 순으로 고른다(설계 2.2 표). LIMIT 은 실행 가능 조건 뒤에 건다.
//
//	DVR 동결     dvr_state='open'
//	좌초 회수     dvr_state='freezing' ∧ now − dvr_freezing_at > $4(좌초 판정)
//	사슬 닫힘     dvr 종국 ∧ vod none ∧ 후속 없음 ∧ init 판정 끝 ∧ (즉시 닫는 사유 $6 ∨ 표식 ∨ now − LastEnd > $5)
//	정산 · VOD   dvr 종국 ∧ vod freezing ∧ 후속 없음
//	phase 4      dvr 종국 ∧ vod 종국 ∧ 후속 없음
//
// 고르지 않는 행은 같은 회차 후속이 있는 비꼬리(꼬리를 기다릴 뿐이다)와 창이 아직 안 지난 꼬리다. $2 · $3 은 커서다
// ($2 NULL = 처음부터). $7 은 쪽 크기다.
const listEndingSessionsSQL = `
SELECT * FROM (` + endingRowSelectSQL + `
 WHERE p.state = 'ending'
   AND ($2::timestamptz IS NULL OR (p.ending_at, p.session_id) > ($2, $3::text))) r
 WHERE r.dvr_state = 'open'
    OR (r.dvr_state = 'freezing' AND r.now - r.dvr_freezing_at > $4::interval)
    OR (r.dvr_state IN ('frozen', 'freeze_failed') AND NOT r.has_successor
        AND (r.vod_state <> 'none'
             OR (r.init_settled AND (r.end_reason = ANY($6::text[]) OR r.stop_marked_at IS NOT NULL
                                     OR r.now - r.last_end > $5::interval))))
 ORDER BY r.ending_at, r.session_id
 LIMIT $7`

// loadEndingSessionSQL 은 빠른 길 — 세션 $2 하나를 같은 판독식으로 읽는다(ending 일 때만).
const loadEndingSessionSQL = endingRowSelectSQL + `
 WHERE p.session_id = $2 AND p.state = 'ending'`

// endingArgs 는 정산 스캔의 인자다.
func endingArgs(after ScanCursor, limit int, o CloseOptions) []any {
	var at any
	if !after.EndingAt.IsZero() {
		at = after.EndingAt
	}
	return []any{SettleWindow, at, after.SessionID, FreezeOrphanAfter, o.closeAfter(), chainCloseReasons, limit}
}

// ListEndingSessions 는 state='ending' 가운데 지금 다음 걸음이 있는 행만 (ending_at, session_id) 순으로 after 뒤에서
// limit 개 고른다. 고른 행이 limit 보다 적으면 next 는 영값이다(다음 스캔은 처음부터). 정산 대기 꼬리(실행 가능이지만
// 그 스캔에서 진행하지 않을 수 있다)가 쪽을 채워도 뒤 행이 굶지 않는 것이 커서의 몫이다(D66).
func ListEndingSessions(ctx context.Context, pool *pgxpool.Pool, after ScanCursor, limit int, o CloseOptions) (rows []EndingSession, next ScanCursor, err error) {
	if limit <= 0 {
		return nil, ScanCursor{}, fmt.Errorf("index: 정산 스캔 쪽 크기가 양수가 아니다(%d)", limit)
	}
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	r, err := pool.Query(qctx, listEndingSessionsSQL, endingArgs(after, limit, o)...)
	if err != nil {
		return nil, ScanCursor{}, fmt.Errorf("정산 스캔 실패: %w", err)
	}
	rows, err = pgx.CollectRows(r, scanEndingSession)
	if err != nil {
		return nil, ScanCursor{}, fmt.Errorf("정산 스캔 읽기 실패: %w", err)
	}
	if len(rows) == limit {
		last := rows[len(rows)-1]
		next = ScanCursor{EndingAt: last.EndingAt, SessionID: last.SessionID}
	}
	return rows, next, nil
}

// LoadEndingSession 은 빠른 길의 판독이다 — 세션 하나를 정산 스캔과 같은 판독식으로 읽는다. ending 이 아니거나 없으면
// found=false 다.
func LoadEndingSession(ctx context.Context, pool *pgxpool.Pool, sessionID string) (EndingSession, bool, error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	return readEndingRow(qctx, pool, sessionID)
}

// rowQuerier 는 풀과 트랜잭션이 함께 갖는 한 행 질의다.
type rowQuerier interface {
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
}

// readEndingRow 는 판독식 한 행이다 — CloseChain 이 ③ 뒤 같은 트랜잭션에서 다시 읽을 때도 쓴다.
func readEndingRow(ctx context.Context, q rowQuerier, sessionID string) (EndingSession, bool, error) {
	r, err := q.Query(ctx, loadEndingSessionSQL, SettleWindow, sessionID)
	if err != nil {
		return EndingSession{}, false, fmt.Errorf("종료 세션 판독 실패 session_id=%q: %w", sessionID, err)
	}
	s, err := pgx.CollectExactlyOneRow(r, scanEndingSession)
	if errors.Is(err, pgx.ErrNoRows) {
		return EndingSession{}, false, nil
	}
	if err != nil {
		return EndingSession{}, false, fmt.Errorf("종료 세션 판독 읽기 실패 session_id=%q: %w", sessionID, err)
	}
	return s, true, nil
}

func scanEndingSession(r pgx.CollectableRow) (EndingSession, error) {
	var (
		s                    EndingSession
		freezingAt, markedAt *time.Time
	)
	err := r.Scan(&s.SessionID, &s.StreamID, &s.RoundID, &s.EndReason, &s.DVRState, &s.VODState, &s.InheritsSession,
		&s.EndingAt, &freezingAt, &s.LastEnd, &markedAt, &s.Now, &s.HasSuccessor, &s.HasPublishTrace, &s.InitSettled)
	if freezingAt != nil {
		s.DVRFreezingAt = *freezingAt
	}
	if markedAt != nil {
		s.StopMarkedAt = *markedAt
	}
	return s, err
}
