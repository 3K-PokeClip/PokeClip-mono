package index

// 되감기 캐시의 적재 읽기와 30초 감시 읽기(POK-195 M4 PR ⓑ · PR ⓒ 커밋 5 — 설계 4.1 「부팅 재구성」 · 「정합성
// 감시」 · 계획 2.3 ⑸ⓕ · 4.5 A2 결정 11 · A3 결정 3 · 4).
//
// 읽기 전용이다 — 장부 쓰기 0 이고 INSERT 경로(store.go)를 건드리지 않는다. 행을 가져올 뿐 settled
// 판정은 하지 않는다: 판정은 rewind/boundary.Settled 하나이고 캐시·재구성·정합성 감사가 모두 그것을
// 부른다(착수 점검 숨은 가정 6). 판정이 SQL 에도 있으면 캐시와 감사가 서로 다른 접두를 본다.

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// RewindRow 는 되감기 캐시가 싣는 장부 한 행이다 — 설계 4.1 settled 정본 SQL 의 열에서
// is_discontinuity 를 뺀 것이다. 끊김 표시는 그 열을 근거로 쓰지 않으므로(계획 부기 29) 싣지 않는다 —
// 실리지 않은 값은 표시 규칙이 잘못 읽을 수도 없다(계획 6.3 #7·#42 의 방어가 타입에 선다).
//
// NULL 은 영값이다(SeedResult 와 같은 규약). 세 carrier 열에는 NULL 아니면 실제 값이 들어가므로
// 영값과 겹치지 않는다.
type RewindRow struct {
	Seq int64
	// SessionID 는 session_id 다. "" 면 NULL(비귀속)이다.
	SessionID  string
	DurationMS int32
	// PlaybackPDT 는 playback_pdt 다. 영값이면 NULL 이다.
	PlaybackPDT time.Time
	// PlaybackS3Key 는 playback_s3_key 다. "" 면 NULL 이다.
	PlaybackS3Key string
	// PlaybackUploaded 는 playback_upload_state = 'uploaded' 인가다 — ② 가 아니라 ③ 축이다.
	PlaybackUploaded bool
	// IsGap 은 (stream_id, seq) 가 발행된 GAP 원장(stream_published_gaps)에 있는가다.
	IsGap bool
}

// RewindSession 은 되감기 캐시가 싣는 회차(stream_sessions) 한 행의 여덟 열과 회차 최소 seq 다(계획
// 2.3 ⑸ⓕ⑵ · ④ 착수 메모의 target_duration 추가 · 4.5 A3 결정 3). NULL 은 영값이다.
type RewindSession struct {
	SessionID string
	// State 는 state 다 — live·ending·ended(DDL CHECK 가 정본).
	State string
	// EndReason 은 end_reason 이다. "" 면 NULL 이다.
	EndReason string
	// InitUploaded 는 init_uploaded_at 이 있는가다 — 그 회차의 MAP 이 가리키는 init 이 올라가 있다.
	InitUploaded      bool
	DiscontinuityBase int64
	// FirstPDT 는 first_pdt 다. 영값이면 NULL 이다.
	FirstPDT time.Time
	// InheritsSession 은 inherits_session 이다. "" 면 NULL 이다.
	InheritsSession string
	TargetDuration  int32
	// MinSeq 는 장부에서 이 회차에 귀속된 행의 가장 작은 seq 다 — 끊김 표시 술어의 재료다
	// (rewind.Session.MinSeq). 적재 하한(RewindLedger.FloorSeq)과 무관한 장부 값이라 하한이 회차 첫 행
	// 뒤여도 표시 자리가 옮겨 가지 않는다(계획 4.5 A3 결정 3 · A1 E1).
	MinSeq int64
}

// RewindLedger 는 한 스트림의 적재분이다 — 되감기 캐시(rewind/cache)가 통째로 갈아 끼우는 입력이다.
type RewindLedger struct {
	// CutoffSeq 는 stream_cutoffs.cutoff_seq 다. HasCutoff 가 거짓이면 컷오프가 없고, 그러면
	// 나머지도 비어 있다(되감기를 제공하지 않는 스트림 — 설계 4.2 ⓑ).
	CutoffSeq int64
	HasCutoff bool
	// FloorSeq 는 뷰 하한이다 — Rows 가 이 seq 부터 이어진다(행이 없으면 다음에 실릴 seq). 컷오프 이상이다.
	FloorSeq int64
	// Capped 는 행 수 상한 때문에 되짚기 창이나 힌트가 가리킨 행을 다 싣지 못했는가다(계획 4.5 A3 결정 3).
	Capped bool
	// Rows 는 seq ≥ FloorSeq 인 행이다(seq 오름차순 · 많아야 행 수 상한).
	Rows []RewindRow
	// Sessions 는 Rows 가 참조하는 회차 전부다(개시 순). 창 경계가 아니라 적재한 행이 기준이다 —
	// 창 밖 행의 회차가 없으면 목록을 고를 때 모르는 회차를 만난다.
	Sessions []RewindSession
}

// LedgerBounds 는 적재 한 번의 범위다(계획 4.5 A3 결정 3). 되짚기와 행 수 상한은 캐시의 튜너블(rewind/cache
// Options)이 준다.
type LedgerBounds struct {
	// Hint 는 하한을 이 seq 까지 내린다(A1 무결성 대조가 실패한 발행본의 MSN). nil 이면 없다.
	Hint *int64
	// Lookback 은 되짚기 길이다 — 머리에서 거꾸로 길이를 더해 이만큼에 닿는 가장 늦은 seq 가 되짚기 하한이다.
	Lookback time.Duration
	// RowCap 은 행 수 상한이다 — 되짚기 합산과 행 적재가 모두 최신 쪽부터 이만큼까지만 본다.
	RowCap int
}

// rewindCutoffSQL 은 스트림의 활성화 컷오프다(stream_id 가 PK 라 많아야 1행).
const rewindCutoffSQL = `SELECT cutoff_seq FROM stream_cutoffs WHERE stream_id = $1`

// rewindFloorSQL 은 적재의 되짚기 하한이다(계획 4.5 A3 결정 3 SQL 그대로). 행 수 상한($4) 안의 최신 행만 거꾸로
// 길이를 더해 되짚기($3 — ms)에 닿는 가장 늦은 seq 를 낸다 — 상한 안에서 닿지 못하면 상한 안 가장 오래된 행이고,
// 그 행이 상한을 다 채웠으면 capped 가 참이다. 행이 없으면 컷오프($2)다. PK 역순 범위 + LIMIT 라 DB 작업량이 상한
// 행 수를 넘지 않는다.
const rewindFloorSQL = `
WITH recent AS (
    SELECT seq, duration_ms
      FROM stream_segments
     WHERE stream_id = $1 AND seq >= $2
     ORDER BY seq DESC
     LIMIT $4
), suffix AS (
    SELECT seq, sum(duration_ms) OVER (ORDER BY seq DESC) AS suffix_ms FROM recent
)
SELECT COALESCE((SELECT max(seq) FROM suffix WHERE suffix_ms >= $3),
                (SELECT min(seq) FROM recent),
                $2)                                              AS floor_seq,
       (SELECT count(*) FROM recent) = $4
         AND NOT EXISTS (SELECT 1 FROM suffix WHERE suffix_ms >= $3) AS capped`

// rewindRowsSQL 은 적재의 조각 축이다 — 설계 4.1 settled 정본 SQL 의 형상에서 범위를 `seq ≥ 적재 하한`
// 으로 열고 최신 쪽부터 행 수 상한($3)까지만 싣는 것이다(계획 4.5 A3 결정 3 둘째). is_discontinuity 는
// 읽지 않는다. GAP 원장은 발행 상태(recorded·put_confirmed·vod_abandoned)와 무관하게 소속만 본다 —
// settled 술어의 「(stream_id,k) ∈ stream_published_gaps」 그대로다.
const rewindRowsSQL = `
SELECT seq, session_id, duration_ms, playback_pdt, playback_s3_key, pb_uploaded, is_gap
  FROM (SELECT s.seq, s.session_id, s.duration_ms, s.playback_pdt, s.playback_s3_key,
               (s.playback_upload_state = 'uploaded') AS pb_uploaded,
               (g.seq IS NOT NULL)                    AS is_gap
          FROM stream_segments s
          LEFT JOIN stream_published_gaps g ON g.stream_id = s.stream_id AND g.seq = s.seq
         WHERE s.stream_id = $1 AND s.seq >= $2
         ORDER BY s.seq DESC
         LIMIT $3) AS r
 ORDER BY seq`

// rewindSessionsSQL 은 적재의 세션 축이다 — 조각 축이 참조한 회차의 여덟 열(PK 조회)과 회차 최소 seq(DB 값 —
// stream_segments_session_idx 의 첫 항목)다.
const rewindSessionsSQL = `
SELECT s.session_id, s.state, s.end_reason, s.init_uploaded_at IS NOT NULL, s.discontinuity_base,
       s.first_pdt, s.inherits_session, s.target_duration,
       (SELECT min(g.seq) FROM stream_segments g
         WHERE g.stream_id = s.stream_id AND g.session_id = s.session_id) AS min_seq
  FROM stream_sessions s
 WHERE s.session_id = ANY($1)
 ORDER BY s.started_at, s.session_id`

// LoadRewindLedger 는 한 스트림의 적재분을 읽는다(설계 4.1 · 계획 4.5 A3 결정 3 · 4). 네 문장이다:
//
//	⑴ 컷오프    없으면 나머지를 읽지 않는다
//	⑵ 되짚기    행 수 상한 안에서 되짚기에 닿는 가장 늦은 seq
//	⑶ 행        적재 하한 = max(컷오프, min(되짚기 하한, 힌트))부터 최신 쪽 행 수 상한까지 + ③ 상태 + GAP 원장 소속
//	⑷ 회차      ⑶ 이 참조하는 모든 회차의 여덟 열 + 회차 최소 seq
//
// 넷은 읽기 전용 · REPEATABLE READ 한 트랜잭션이라 한 스냅숏을 본다. 트랜잭션은 이 함수가 BEGIN 부터 끝까지
// 소유하고 ctx 시한은 트랜잭션 하나에 TxnDeadline 하나다. 되짚기나 힌트가 가리킨 행이 상한을 넘으면 최신 상한만큼만
// 싣고 Capped 가 참이다. 읽기에 실패하면 오류다 — 빈 결과로 삼키면 캐시가 「컷오프 없음」으로 굳어 그 스트림의
// 되감기가 조용히 사라진다.
func LoadRewindLedger(ctx context.Context, pool *pgxpool.Pool, streamID string, b LedgerBounds) (RewindLedger, error) {
	return loadRewindLedger(ctx, pool, streamID, b, TxnDeadline)
}

// loadRewindLedger 는 LoadRewindLedger 의 몸체다 — 시한 deadline 을 받는 것은 같은 패키지의 시험이 문장 시한과
// 되돌림 시한을 함께 줄이기 위해서다.
//
// 실패하면 되돌린다. 되돌림 ctx 는 되돌리는 순간에 부른 쪽 ctx 에서 취소를 떼고 같은 시한을 새로 씌워 만든다 —
// 끝난 ctx 로 되돌리면 pgx 가 연결을 닫아 풀 연결이 버려지고, 미리 만들어 두면 시한을 다 쓴 갈래에서 그 ctx 도
// 끝나 있다. COMMIT 오류 뒤에는 되돌리지 않는다 — pgx 의 Commit 은 결과와 상관없이 트랜잭션을 닫는다(미뤄 둔
// 되돌림은 문장 없이 ErrTxClosed 로 끝난다 — rewind/publish gapTx 와 같은 형).
func loadRewindLedger(ctx context.Context, pool *pgxpool.Pool, streamID string, b LedgerBounds, deadline time.Duration) (RewindLedger, error) {
	tctx, cancel := context.WithTimeout(ctx, deadline)
	defer cancel()
	tx, err := pool.BeginTx(tctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		return RewindLedger{}, fmt.Errorf("되감기 적재 트랜잭션 시작 실패 stream_id=%q: %w", streamID, err)
	}
	defer func() {
		rctx, rcancel := context.WithTimeout(context.WithoutCancel(ctx), deadline)
		defer rcancel()
		_ = tx.Rollback(rctx)
	}()
	l, err := readRewindLedger(tctx, tx, streamID, b)
	if err != nil {
		return RewindLedger{}, err
	}
	if err := tx.Commit(tctx); err != nil {
		return RewindLedger{}, fmt.Errorf("되감기 적재 트랜잭션 확정 실패 stream_id=%q: %w", streamID, err)
	}
	return l, nil
}

// readRewindLedger 는 적재 트랜잭션 tx 안의 네 문장이다(LoadRewindLedger 의 표).
func readRewindLedger(ctx context.Context, tx pgx.Tx, streamID string, b LedgerBounds) (RewindLedger, error) {
	var l RewindLedger
	err := tx.QueryRow(ctx, rewindCutoffSQL, streamID).Scan(&l.CutoffSeq)
	if errors.Is(err, pgx.ErrNoRows) {
		return RewindLedger{}, nil
	}
	if err != nil {
		return RewindLedger{}, fmt.Errorf("되감기 적재 컷오프 조회 실패 stream_id=%q: %w", streamID, err)
	}
	l.HasCutoff = true
	var lookback int64
	err = tx.QueryRow(ctx, rewindFloorSQL, streamID, l.CutoffSeq, b.Lookback.Milliseconds(), int64(b.RowCap)).
		Scan(&lookback, &l.Capped)
	if err != nil {
		return RewindLedger{}, fmt.Errorf("되감기 적재 하한 조회 실패 stream_id=%q: %w", streamID, err)
	}
	l.FloorSeq = ledgerFloor(l.CutoffSeq, lookback, b.Hint)
	if l.Rows, err = loadRewindRows(ctx, tx, streamID, l.FloorSeq, b.RowCap); err != nil {
		return RewindLedger{}, err
	}
	if n := len(l.Rows); n > 0 && n == b.RowCap && l.Rows[0].Seq > l.FloorSeq { // 힌트가 상한을 넘겼다
		l.FloorSeq, l.Capped = l.Rows[0].Seq, true
	}
	if l.Sessions, err = loadRewindSessions(ctx, tx, streamID, referencedSessions(l.Rows)); err != nil {
		return RewindLedger{}, err
	}
	return l, nil
}

// ledgerFloor 는 적재 하한이다 — max(cutoff, min(lookback, hint))(계획 4.5 A3 결정 3). 힌트는 하한을 내리기만 한다.
func ledgerFloor(cutoff, lookback int64, hint *int64) int64 {
	floor := lookback
	if hint != nil {
		floor = min(floor, *hint)
	}
	return max(floor, cutoff)
}

// loadRewindRows 는 조각 축(⑶)이다.
func loadRewindRows(ctx context.Context, tx pgx.Tx, streamID string, floor int64, rowCap int) ([]RewindRow, error) {
	rows, err := tx.Query(ctx, rewindRowsSQL, streamID, floor, int64(rowCap))
	if err != nil {
		return nil, fmt.Errorf("되감기 적재 조각 조회 실패 stream_id=%q: %w", streamID, err)
	}
	defer rows.Close()

	var out []RewindRow
	for rows.Next() {
		var (
			r         RewindRow
			sessionID *string
			pdt       *time.Time
			key       *string
		)
		if err := rows.Scan(&r.Seq, &sessionID, &r.DurationMS, &pdt, &key, &r.PlaybackUploaded, &r.IsGap); err != nil {
			return nil, fmt.Errorf("되감기 적재 조각 스캔 실패 stream_id=%q: %w", streamID, err)
		}
		r.SessionID, r.PlaybackPDT, r.PlaybackS3Key = deref(sessionID), derefTime(pdt), deref(key)
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("되감기 적재 조각 조회 중 오류 stream_id=%q: %w", streamID, err)
	}
	return out, nil
}

// referencedSessions 는 행들이 참조하는 회차 ID 다(처음 나온 순서 · 중복 없음 · 비귀속 행 제외).
func referencedSessions(rows []RewindRow) []string {
	var ids []string
	seen := map[string]bool{}
	for _, r := range rows {
		if r.SessionID != "" && !seen[r.SessionID] {
			seen[r.SessionID] = true
			ids = append(ids, r.SessionID)
		}
	}
	return ids
}

// loadRewindSessions 는 세션 축(⑷)이다.
func loadRewindSessions(ctx context.Context, tx pgx.Tx, streamID string, ids []string) ([]RewindSession, error) {
	rows, err := tx.Query(ctx, rewindSessionsSQL, ids)
	if err != nil {
		return nil, fmt.Errorf("되감기 적재 회차 조회 실패 stream_id=%q: %w", streamID, err)
	}
	defer rows.Close()

	var out []RewindSession
	for rows.Next() {
		var (
			s                  RewindSession
			endReason, inherit *string
			firstPDT           *time.Time
		)
		if err := rows.Scan(&s.SessionID, &s.State, &endReason, &s.InitUploaded, &s.DiscontinuityBase,
			&firstPDT, &inherit, &s.TargetDuration, &s.MinSeq); err != nil {
			return nil, fmt.Errorf("되감기 적재 회차 스캔 실패 stream_id=%q: %w", streamID, err)
		}
		s.EndReason, s.FirstPDT, s.InheritsSession = deref(endReason), derefTime(firstPDT), deref(inherit)
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("되감기 적재 회차 조회 중 오류 stream_id=%q: %w", streamID, err)
	}
	return out, nil
}

// WatchProbe 는 30초 감시의 드리프트 대조 입력 한 스트림이다 — 캐시 머리 H 의 다음 seq(H+1)를 DB 에서 본다(계획
// 4.5 A3 결정 5 · 체크리스트 J39). 캐시 컷오프는 싣지 않는다 — 문장이 DB 컷오프를 실어 돌려주고 대조는 캐시가 한다.
type WatchProbe struct {
	StreamID string
	NextSeq  int64
}

// WatchLive 는 감시 결과의 live 회차 한 행이다(계획 4.5 A2 결정 11 의 열 다섯).
type WatchLive struct {
	StreamID  string
	SessionID string
	// InitUploaded 는 init_uploaded_at 이 있는가다.
	InitUploaded bool
	// InheritAbsent 는 inherits_session 이 NULL 인가다(계승이 없거나 풀렸다).
	InheritAbsent bool
	// HasCutoff 는 그 스트림에 컷오프가 있는가다 — 부팅 적재 대상을 고른다(A3 결정 6).
	HasCutoff bool
}

// WatchDrift 는 감시 결과의 드리프트 재료 한 행이다 — 입력(WatchProbe) 하나마다 한 행이다. 판정(settled 인가)은
// 싣지 않는다(B #14 — 판정은 rewind/boundary.Settled 한 곳).
type WatchDrift struct {
	StreamID string
	// NextSeq 는 대조한 seq(입력의 H+1)다.
	NextSeq int64
	// CutoffSeq 는 DB 컷오프다. HasCutoff 가 거짓이면 DB 에 컷오프가 없다.
	CutoffSeq int64
	HasCutoff bool
	// Next 는 DB 의 NextSeq 행이다(적재와 같은 열). HasNext 가 거짓이면 그 행이 없다.
	Next    RewindRow
	HasNext bool
}

// WatchAbsent 는 감시 결과의 컷오프 부재 한 행이다(설계 4.1 (c)) — 컷오프가 없고 마지막 조각이 SEED_ALARM_AFTER 안인
// 스트림이다.
type WatchAbsent struct {
	StreamID string
	// LastWall 은 그 스트림의 마지막 조각 시각(max(start_wall_utc))이다.
	LastWall time.Time
}

// RewindWatch 는 30초 감시 한 문장의 결과다 — 종류마다 한 목록이다(각 목록은 스트림 순).
type RewindWatch struct {
	Live   []WatchLive
	Drift  []WatchDrift
	Absent []WatchAbsent
}

// InitRepair 는 감시 결과가 캐시에 되살린 잃은 init 결과 한 회차다(계획 4.5 A2 결정 11) — 감시 화해(rewind/cache)가
// 돌려주고 발행 층의 로그 래퍼가 cache_drift_repaired(kind=init_uploaded)로 남긴다. Revoked 는 같은 반영에서 계승을
// 풀었는가다. 발행 층이 캐시를 임포트하지 않아(계획 4.5 A3 「동시성」) 두 쪽이 함께 읽는 이 패키지에 둔다.
type InitRepair struct {
	StreamID  string
	SessionID string
	Revoked   bool
}

// rewindWatchSQL 은 30초 감시 한 문장이다(설계 4.1 (a) (c) · 계획 4.5 A2 결정 11 · 체크리스트 A-6 1 · 판단 J41) —
// CTE 넷과 종류 열로 한 결과다(ids 는 absent 의 재료라 종류는 셋이다). 바인드는 셋이다: $1 · $2 = 대조할 스트림과 그
// H+1(자리로 짝짓는 배열) · $3 = SEED_ALARM_AFTER.
//
//	live    state = 'live' 회차의 열 다섯(A2 결정 11 SQL 그대로)
//	drift   입력 스트림마다 DB 컷오프와 H+1 행의 settled 재료(적재 행 문장과 같은 열) — 판정하지 않는다
//	ids     장부의 스트림 ID 전부 — 색인에서 「지금보다 큰 첫 스트림 ID」만 골라 건너뛰며 뽑는다(재귀 — 문장 머리의
//	        WITH RECURSIVE 는 이 CTE 때문이다). 마지막 스트림 뒤의 NULL 한 행에서 멈춘다
//	absent  컷오프 없는 스트림 가운데 마지막 조각이 $3 안인 것 — 설계 4.1 (c) 문장과 뜻이 같다. 스트림은 ids 에서 받고
//	        마지막 조각 시각은 스트림마다 색인(stream_segments_wall_idx)의 첫 항목으로 읽는다. 조각 표 전체를
//	        GROUP BY 로 훑지 않는다 — 행을 지우는 코드가 아직 없어 그 비용이 운영 기간 전체에 비례하고, 한 문장이라
//	        시한(TxnDeadline)을 넘기면 (a) · (c) · 화해가 함께 빠진다. 이 형의 비용은 스트림 ID 가짓수에 비례한다.
//	        live 와 독립이다(f4 — live 회차가 없는 스캔 단독 국면에서도 참이어야 한다)
const rewindWatchSQL = `
WITH RECURSIVE live AS (
    SELECT s.stream_id, s.session_id,
           (s.init_uploaded_at IS NOT NULL) AS init_uploaded,
           (s.inherits_session IS NULL)     AS inherit_absent,
           (c.stream_id IS NOT NULL)        AS has_cutoff
      FROM stream_sessions s
      LEFT JOIN stream_cutoffs c ON c.stream_id = s.stream_id
     WHERE s.state = 'live'
), drift AS (
    SELECT p.stream_id, p.next_seq, c.cutoff_seq,
           s.seq, s.session_id, s.duration_ms, s.playback_pdt, s.playback_s3_key,
           (s.playback_upload_state = 'uploaded') AS pb_uploaded,
           (g.seq IS NOT NULL)                    AS is_gap
      FROM unnest($1::text[], $2::bigint[]) AS p(stream_id, next_seq)
      LEFT JOIN stream_cutoffs c ON c.stream_id = p.stream_id
      LEFT JOIN stream_segments s ON s.stream_id = p.stream_id AND s.seq = p.next_seq
      LEFT JOIN stream_published_gaps g ON g.stream_id = p.stream_id AND g.seq = p.next_seq
), ids AS (
    SELECT min(stream_id) AS stream_id FROM stream_segments
    UNION ALL
    SELECT (SELECT min(x.stream_id) FROM stream_segments x WHERE x.stream_id > ids.stream_id)
      FROM ids WHERE ids.stream_id IS NOT NULL
), absent AS (
    SELECT i.stream_id, w.last_wall
      FROM ids i
      LEFT JOIN stream_cutoffs c ON c.stream_id = i.stream_id
      CROSS JOIN LATERAL (SELECT max(s.start_wall_utc) AS last_wall
                            FROM stream_segments s WHERE s.stream_id = i.stream_id) w
     WHERE i.stream_id IS NOT NULL AND c.stream_id IS NULL AND now() - w.last_wall <= $3::interval
)
SELECT 'live' AS kind, stream_id, session_id, init_uploaded, inherit_absent, has_cutoff,
       NULL::bigint AS next_seq, NULL::bigint AS cutoff_seq, NULL::bigint AS seq, NULL::text AS row_session,
       NULL::int AS duration_ms, NULL::timestamptz AS playback_pdt, NULL::text AS playback_s3_key,
       NULL::boolean AS pb_uploaded, NULL::boolean AS is_gap, NULL::timestamptz AS last_wall
  FROM live
UNION ALL
SELECT 'drift', stream_id, NULL, NULL, NULL, NULL,
       next_seq, cutoff_seq, seq, session_id, duration_ms, playback_pdt, playback_s3_key, pb_uploaded, is_gap, NULL
  FROM drift
UNION ALL
SELECT 'absent', stream_id, NULL, NULL, NULL, NULL,
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, last_wall
  FROM absent
 ORDER BY kind, stream_id, session_id, next_seq`

// LoadRewindWatch 는 30초 감시 한 문장을 읽는다(rewindWatchSQL — 풀 단일 문장 · 트랜잭션 없음). probes 는 드리프트
// 대조 입력이고 seedAlarmAfter 는 (c) 의 창이다. 문장 시한은 TxnDeadline 이다(작업 안에서 — Lanes.Start 의 ctx 는 루프
// 수명이다). 읽기에 실패하면 오류다 — 그 틱의 (a) · (c) · 화해를 건너뛰는 것은 부르는 쪽 몫이다(판단 J46).
func LoadRewindWatch(ctx context.Context, pool *pgxpool.Pool, probes []WatchProbe, seedAlarmAfter time.Duration) (RewindWatch, error) {
	ctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	streams, seqs := make([]string, 0, len(probes)), make([]int64, 0, len(probes))
	for _, p := range probes {
		streams, seqs = append(streams, p.StreamID), append(seqs, p.NextSeq)
	}
	rows, err := pool.Query(ctx, rewindWatchSQL, streams, seqs, seedAlarmAfter)
	if err != nil {
		return RewindWatch{}, fmt.Errorf("되감기 30초 감시 조회 실패: %w", err)
	}
	defer rows.Close()

	var w RewindWatch
	for rows.Next() {
		if err := scanWatchRow(rows, &w); err != nil {
			return RewindWatch{}, err
		}
	}
	if err := rows.Err(); err != nil {
		return RewindWatch{}, fmt.Errorf("되감기 30초 감시 조회 중 오류: %w", err)
	}
	return w, nil
}

// scanWatchRow 는 감시 결과 한 행을 종류 열에 따라 w 의 목록에 더한다. 종류마다 쓰지 않는 열은 NULL 이다.
func scanWatchRow(rows pgx.Rows, w *RewindWatch) error {
	var (
		kind, stream                string
		session, rowSession, key    *string
		initUploaded, inheritAbsent *bool
		hasCutoff, uploaded, gap    *bool
		nextSeq, cutoff, seq        *int64
		duration                    *int32
		pdt, lastWall               *time.Time
	)
	if err := rows.Scan(&kind, &stream, &session, &initUploaded, &inheritAbsent, &hasCutoff,
		&nextSeq, &cutoff, &seq, &rowSession, &duration, &pdt, &key, &uploaded, &gap, &lastWall); err != nil {
		return fmt.Errorf("되감기 30초 감시 스캔 실패: %w", err)
	}
	switch kind {
	case "live":
		w.Live = append(w.Live, WatchLive{StreamID: stream, SessionID: deref(session),
			InitUploaded: *initUploaded, InheritAbsent: *inheritAbsent, HasCutoff: *hasCutoff})
	case "drift":
		d := WatchDrift{StreamID: stream, NextSeq: *nextSeq, HasCutoff: cutoff != nil, HasNext: seq != nil}
		if d.HasCutoff {
			d.CutoffSeq = *cutoff
		}
		if d.HasNext {
			d.Next = RewindRow{Seq: *seq, SessionID: deref(rowSession), DurationMS: *duration, PlaybackPDT: derefTime(pdt),
				PlaybackS3Key: deref(key), PlaybackUploaded: *uploaded, IsGap: *gap}
		}
		w.Drift = append(w.Drift, d)
	case "absent":
		w.Absent = append(w.Absent, WatchAbsent{StreamID: stream, LastWall: derefTime(lastWall)})
	}
	return nil
}

// deref 는 NULL 가능 text 를 영값 규약("" = NULL)으로 편다.
func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// derefTime 은 NULL 가능 timestamptz 를 영값 규약(영값 = NULL)으로 편다. 저장은 UTC 강제지만 드라이버가
// 로컬 위치로 실어 올 수 있어 여기서 한 번 못 박는다(LoadCursor 와 같은 이유).
func derefTime(t *time.Time) time.Time {
	if t == nil {
		return time.Time{}
	}
	return t.UTC()
}
