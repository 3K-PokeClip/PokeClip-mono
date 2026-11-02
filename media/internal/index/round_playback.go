package index

// 회차 상태 읽기 셋(POK-196 M6-3a · 설계 M6 2.2 판정식 · 경계 확정 2 · 3 · 4). 세 함수는 대상 회차를 고르는 앞머리만
// 다르고 판정식(playbackVerdictSQL)은 한 글자다 — 그래서 회차 하나 · 스트림 최신 회차 · 기동 적재가 같은 회차에 같은
// 값을 낸다. 판정은 한 SQL 문장이다(READ COMMITTED 에서 스냅숏 하나 — 두 문장으로 나누면 그 사이 커밋이 섞인다).

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// playbackVerdictSQL 은 판정식이다 — 앞머리가 준 targets(stream_id, round_key)마다 한 행을 낸다.
//
// 회차 열쇠(round_key)는 round_id 다. M6 전 세션(round_id NULL)은 그 세션 하나가 회차이고(열쇠 = session_id) 닫힘 개념이
// 없어 state='ended' 면 완결이다. 회차의 최신 세션은 3.3 선택 규칙(그 회차에 live 가 있으면 그 live, 없으면 started_at
// 최대 · 같으면 session_id 큰 쪽)이다. 국면은 위에서 처음 맞는 줄이다:
//
//	회차에 live 세션 있음                                      live    (스트림 · 기동 적재는 값 없음)
//	회차 세션에 expired_at 있음                                unknown
//	완결 아님(ended 아닌 세션 있음 ∨ round_closed_at 없음)     ending
//	최신 세션 vod_state ∈ {frozen, frozen_with_gaps} ∧ 키 있음 vod     (꼬리 사슬 VOD — 사슬 전원이 같은 vod_* 를 든다)
//	그 밖(freeze_failed · 빈 동결)                             ended
//
// 완결 시각은 GREATEST(round_closed_at, max(ended_at)) 다. VOD 범위의 첫 조각 PDT 와 마지막 조각 끝(배타)은 그 사슬의
// vod_first_seq · vod_last_seq 조각에서 읽는다(PK 조회). 집계 함수는 pg_catalog 로 한정하고, GREATEST · COALESCE 는 구문이라
// 한정하지 않는다. duration_ms 는 registry 의 previousSessionSQL 과 같은 까닭으로 ::double precision 으로 맞춘다.
const playbackVerdictSQL = `,
members AS (
  SELECT t.stream_id, t.round_key, s.session_id, s.state, s.started_at, s.round_id, s.round_closed_at,
         s.ended_at, s.expired_at, s.vod_state, s.vod_s3_key, s.vod_first_seq, s.vod_last_seq
    FROM targets t
    JOIN stream_sessions s
      ON s.stream_id = t.stream_id
     AND (s.round_id = t.round_key OR (s.round_id IS NULL AND s.session_id = t.round_key))
),
agg AS (
  SELECT stream_id, round_key,
         pg_catalog.bool_or(state = 'live')            AS has_live,
         pg_catalog.bool_or(expired_at IS NOT NULL)    AS expired,
         pg_catalog.bool_and(state = 'ended')          AS all_ended,
         pg_catalog.bool_or(round_id IS NULL)          AS legacy,
         pg_catalog.max(round_closed_at)               AS closed_at,
         pg_catalog.max(ended_at)                      AS last_ended
    FROM members
   GROUP BY stream_id, round_key
),
latest AS (
  SELECT DISTINCT ON (stream_id, round_key)
         stream_id, round_key, session_id, vod_state, vod_s3_key, vod_first_seq, vod_last_seq
    FROM members
   ORDER BY stream_id, round_key, (state = 'live') DESC, started_at DESC, session_id DESC
)
SELECT a.stream_id, a.round_key, l.session_id,
       CASE WHEN a.has_live THEN 'live'
            WHEN a.expired THEN 'unknown'
            WHEN NOT a.all_ended OR (NOT a.legacy AND a.closed_at IS NULL) THEN 'ending'
            WHEN l.vod_state IN ('frozen', 'frozen_with_gaps') AND l.vod_s3_key IS NOT NULL THEN 'vod'
            ELSE 'ended' END,
       GREATEST(a.closed_at, a.last_ended), pg_catalog.now(),
       l.vod_s3_key, f.playback_pdt,
       h.playback_pdt + h.duration_ms::double precision * interval '1 millisecond'
  FROM agg a
  JOIN latest l ON l.stream_id = a.stream_id AND l.round_key = a.round_key
  LEFT JOIN stream_segments f ON f.stream_id = a.stream_id AND f.seq = l.vod_first_seq
  LEFT JOIN stream_segments h ON h.stream_id = a.stream_id AND h.seq = l.vod_last_seq`

// lookupRoundPlaybackSQL 은 회차 하나다($1 stream · $2 round).
const lookupRoundPlaybackSQL = `
WITH targets AS (SELECT $1::text AS stream_id, $2::text AS round_key)` + playbackVerdictSQL

// loadStreamPlaybackSQL 은 스트림의 최신 회차 — 소속 행(3.3 선택 규칙: live 가 있으면 live, 없으면 started_at 최대)의 회차다.
// 두 하위 질의는 각각 stream_sessions_one_live_uq · stream_sessions_stream_idx 로 간다.
const loadStreamPlaybackSQL = `
WITH targets AS (
  SELECT $1::text AS stream_id, m.round_key
    FROM (SELECT COALESCE(
            (SELECT COALESCE(round_id, session_id) FROM stream_sessions WHERE stream_id = $1 AND state = 'live'),
            (SELECT COALESCE(round_id, session_id) FROM stream_sessions WHERE stream_id = $1
              ORDER BY started_at DESC, session_id DESC LIMIT 1)) AS round_key) m
   WHERE m.round_key IS NOT NULL)` + playbackVerdictSQL

// loadPlaybackStatesSQL 은 모든 스트림의 최신 회차다(기동 때 한 번 — 같은 선택 규칙을 DISTINCT ON 으로).
const loadPlaybackStatesSQL = `
WITH targets AS (
  SELECT DISTINCT ON (stream_id) stream_id, COALESCE(round_id, session_id) AS round_key
    FROM stream_sessions
   ORDER BY stream_id, (state = 'live') DESC, started_at DESC, session_id DESC)` + playbackVerdictSQL

// roundVerdict 는 판정식 한 행이다.
type roundVerdict struct {
	streamID    string
	st          PlaybackState
	completedAt time.Time // 완결 시각(없으면 영값)
	now         time.Time // 그 문장의 pg_catalog.now() — 판정 시계 하나
}

// LookupRoundPlayback 은 회차 하나의 국면이다(회차별 조회 · 경계 확정 4). 그 회차의 국면만 낸다 — 같은 스트림 다른 회차의
// 값을 섞지 않는다. 회차에 live 가 있으면 PhaseLive 다. 모르는 회차 · 다른 스트림의 회차 · 만료는 PhaseUnknown 이다.
func LookupRoundPlayback(ctx context.Context, pool *pgxpool.Pool, ingestStreamID, roundID string) (PlaybackState, error) {
	vs, err := queryVerdicts(ctx, pool, lookupRoundPlaybackSQL, ingestStreamID, roundID)
	if err != nil {
		return PlaybackState{}, fmt.Errorf("회차 상태 조회 실패 stream_id=%q round_id=%q: %w", ingestStreamID, roundID, err)
	}
	if len(vs) == 0 {
		return PlaybackState{Phase: PhaseUnknown}, nil
	}
	return vs[0].st, nil
}

// LoadStreamPlayback 은 스트림 하나의 지금 상태다 — 상태 전달 차선 전용(평시 경로 아님). ok=false 면 보낼 값이 없다(최신
// 회차가 live). 장부에 없는 스트림은 PhaseUnknown(회차 빈 값)이다. 시한은 풀 획득부터 읽기까지 TxnDeadline 하나다 — 차선이
// 주는 ctx 는 루프 수명이라, 시한이 없으면 멎은 읽기가 동시 상한의 칸을 계속 쥔다.
func LoadStreamPlayback(ctx context.Context, pool *pgxpool.Pool, streamID string) (st PlaybackState, ok bool, err error) {
	qctx, cancel := context.WithTimeout(ctx, TxnDeadline)
	defer cancel()
	vs, err := queryVerdicts(qctx, pool, loadStreamPlaybackSQL, streamID)
	if err != nil {
		return PlaybackState{}, false, fmt.Errorf("스트림 상태 조회 실패 stream_id=%q: %w", streamID, err)
	}
	if len(vs) == 0 {
		return PlaybackState{Phase: PhaseUnknown}, true, nil
	}
	if vs[0].st.Phase == PhaseLive {
		return PlaybackState{}, false, nil
	}
	return vs[0].st, true, nil
}

// LoadPlaybackStates 는 기동 때 한 번이다(경계 확정 3). 판정은 LoadStreamPlayback 과 같다. Ending · VOD · Ended 만 내고,
// within 은 완결 값(VOD · Ended)에만 건다 — 완결 안 된 회차의 Ending 에는 걸지 않는다(오프라인 종료 뒤 회차 닫힘은 360초
// 뒤라, 그 앞에 재기동하면 부팅 캐시와 적재 둘 다에서 빠지기 때문이다 — D67). live · Unknown 은 내지 않는다.
func LoadPlaybackStates(ctx context.Context, pool *pgxpool.Pool, within time.Duration) (map[string]PlaybackState, error) {
	vs, err := queryVerdicts(ctx, pool, loadPlaybackStatesSQL)
	if err != nil {
		return nil, fmt.Errorf("기동 상태 적재 실패: %w", err)
	}
	out := make(map[string]PlaybackState, len(vs))
	for _, v := range vs {
		switch v.st.Phase {
		case PhaseEnding:
			out[v.streamID] = v.st
		case PhaseVOD, PhaseEnded:
			if !v.completedAt.IsZero() && !v.completedAt.Before(v.now.Add(-within)) {
				out[v.streamID] = v.st
			}
		}
	}
	return out, nil
}

// queryVerdicts 는 판정식 문장을 돌려 행을 편다.
func queryVerdicts(ctx context.Context, pool *pgxpool.Pool, sql string, args ...any) ([]roundVerdict, error) {
	rows, err := pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, scanVerdict)
}

func scanVerdict(r pgx.CollectableRow) (roundVerdict, error) {
	var (
		v                      roundVerdict
		phase                  string
		completed, first, head *time.Time
		vodKey                 *string
	)
	if err := r.Scan(&v.streamID, &v.st.RoundID, &v.st.LatestSessionID, &phase,
		&completed, &v.now, &vodKey, &first, &head); err != nil {
		return roundVerdict{}, err
	}
	ph, err := phaseOf(phase)
	if err != nil {
		return roundVerdict{}, err
	}
	v.st.Phase = ph
	if completed != nil {
		v.completedAt = completed.UTC()
	}
	if ph == PhaseVOD {
		// 범위 조각이 없으면 PDT 가 영값으로 남는다 — 장부가 뒤틀린 결함이라 M5 변환(FromPlaybackState)이 무효로 거른다.
		v.st.VODKey = *vodKey
		if first != nil {
			v.st.FirstPDT = first.UTC()
		}
		if head != nil {
			v.st.HeadPDT = head.UTC()
		}
	}
	return v, nil
}

// phaseOf 는 판정식의 글자를 국면으로 옮긴다. 모르는 글자는 판정식 결함이라 오류다.
func phaseOf(s string) (PlaybackPhase, error) {
	switch s {
	case "live":
		return PhaseLive, nil
	case "unknown":
		return PhaseUnknown, nil
	case "ending":
		return PhaseEnding, nil
	case "vod":
		return PhaseVOD, nil
	case "ended":
		return PhaseEnded, nil
	}
	return 0, fmt.Errorf("index: 판정식이 모르는 국면 %q 를 냈다", s)
}
