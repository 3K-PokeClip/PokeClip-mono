package broadcast

// 장부 트랜잭션 쪽 outbox 문장 — 개시(started)와 완결 평가(ended)(설계 M6 15.4 · 15.5). 둘 다 부르는 쪽의 트랜잭션에서
// 돌아 장부와 원자다(K9 ㈎) — 롤백되면 outbox 행도 없고, 발행기는 커밋된 행만 본다(D37).

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Gate 는 발행 켜기 게이트다(15.5 · 7절 활성화 선행 조건) — 어느 stream_id 의 회차를 outbox 에 넣는지와, 넣은 행을
// 알리는 로거를 든다. 영값은 꺼짐이다. 주제 ARN 이 없거나 outbox 권한이 없으면 부르는 쪽(기동 조립)이 영값을 그대로
// 쓴다 — 그래서 꺼진 판의 개시 · phase 4 는 outbox 문장을 하나도 보내지 않는다(권한이 없어도 42501 로 막히지 않는다).
type Gate struct {
	all     bool
	streams map[string]struct{}
	log     *slog.Logger
}

// NewGate 는 허용 목록(LIFECYCLE_PUBLISH_STREAMS 를 쉼표로 가른 값)으로 게이트를 만든다. "*" 하나라도 있으면 모든
// 스트림이고, 항목 앞뒤 공백 · 빈 항목은 버린다. 남는 항목이 없으면 꺼짐이다. log 가 nil 이면 버린다.
func NewGate(streams []string, log *slog.Logger) Gate {
	g := Gate{streams: map[string]struct{}{}, log: log}
	for _, s := range streams {
		s = strings.TrimSpace(s)
		switch s {
		case "":
		case "*":
			g.all = true
		default:
			g.streams[s] = struct{}{}
		}
	}
	return g
}

// Enabled 는 게이트가 어떤 스트림이든 내는지다.
func (g Gate) Enabled() bool { return g.all || len(g.streams) > 0 }

// allows 는 그 stream_id 의 회차를 outbox 에 넣는지다.
func (g Gate) allows(ingestStreamID string) bool {
	if g.all {
		return true
	}
	_, ok := g.streams[ingestStreamID]
	return ok
}

// logger 는 영값 게이트 · nil 로거에서도 nil 이 아닌 로거다.
func (g Gate) logger() *slog.Logger {
	if g.log == nil {
		return slog.New(slog.DiscardHandler)
	}
	return g.log
}

// insertStartedSQL 은 started 행이다. 겹쳐 불려도 행은 하나다(결정적 eventId · 15.5 cc L④).
const insertStartedSQL = `
INSERT INTO broadcast_lifecycle_outbox
       (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, state, last_error)
VALUES ($1, $2, $3, 1, $4, $5, $6, $7)
ON CONFLICT (event_id) DO NOTHING`

// EnqueueStarted 는 새 회차를 여는 트랜잭션 안에서 started 행을 넣는다(15.4 — registry 개시 · 4D round_split).
// startedAt 은 머리 세션 started_at 이다. 게이트가 그 stream_id 를 내지 않거나 round 가 비면(M6 전 세션) 아무 문장도
// 보내지 않는다(D45). 회차 열쇠가 식별자 규칙(CheckRound)을 어기면 행을 abandoned 로 넣고 ERROR 를 남긴다(D48) —
// pending 으로 두면 영영 못 나가고, 행이 없으면 ended 를 막을 근거가 없다. 오류는 INSERT 실패뿐이다.
func EnqueueStarted(ctx context.Context, tx pgx.Tx, g Gate, round, ingestStreamID string, startedAt time.Time) error {
	if round == "" || !g.allows(ingestStreamID) {
		return nil
	}
	state, reason := "pending", ""
	if err := CheckRound(round); err != nil {
		state, reason = "abandoned", abandonReason(err)
	}
	var lastErr *string
	if reason != "" {
		lastErr = &reason
	}
	eventID := StartedEventID(round)
	tag, err := tx.Exec(ctx, insertStartedSQL, eventID, round, EventStarted, startedAt, ingestStreamID, state, lastErr)
	if err != nil {
		return fmt.Errorf("broadcast: started outbox 넣기(회차 %s): %w", round, err)
	}
	if tag.RowsAffected() == 0 {
		return nil
	}
	if reason != "" {
		g.logger().Error("lifecycle_abandoned", "event_id", eventID, "reason", reason)
		return nil
	}
	g.logger().Info("lifecycle_enqueued", "event_type", EventStarted, "round", round)
	return nil
}

// insertEndedSQL 은 회차가 완결(닫힘 ∧ 전원 ended)이고 그 회차 started 행이 살아 있을(abandoned 아님) 때만 ended 행을
// 넣는다($1 started eventId · $2 ended eventId · $3 eventType). 문장 하나라 판정과 INSERT 가 같은 스냅숏이다.
//
//   - 회차 세션은 started 행의 (ingest_stream_id, round_id) 로 찾는다 — stream_sessions_stream_idx 의 선행 열을 쓴다.
//   - occurred_at = 회차 조각의 마지막 끝(D44 — clip endedAt). 조각이 없으면(구조상 드묾) 마지막 ended_at, 그것도 없으면
//     round_closed_at 이다 — NULL 로 넣다 부르는 쪽 트랜잭션을 깨지 않는다.
//   - payload.segmentRange = 회차 조각 seq 최소 · 최대(사슬을 가로지른 회차 전체 — 15.3). 조각이 없으면 {}.
//   - streamer_id 는 넣지 않는다(NULL) — 발행기가 발행 때 started 행에서 읽는다(D62).
//
// duration_ms 를 ::double precision 으로 맞추는 까닭은 registry previousSessionSQL 과 같다.
const insertEndedSQL = `
WITH started AS (
  SELECT round_id, ingest_stream_id FROM broadcast_lifecycle_outbox
   WHERE event_id = $1 AND state <> 'abandoned'
),
members AS (
  SELECT s.session_id, s.stream_id, s.state, s.round_closed_at, s.ended_at
    FROM started st
    JOIN stream_sessions s ON s.stream_id = st.ingest_stream_id AND s.round_id = st.round_id
),
done AS (
  SELECT COALESCE(pg_catalog.max(ended_at), pg_catalog.max(round_closed_at)) AS last_ended
    FROM members
  HAVING pg_catalog.count(*) > 0 AND pg_catalog.bool_and(state = 'ended' AND round_closed_at IS NOT NULL)
),
segs AS (
  SELECT pg_catalog.min(g.seq) AS first_seq, pg_catalog.max(g.seq) AS last_seq,
         pg_catalog.max(g.start_wall_utc + g.duration_ms::double precision * interval '1 millisecond') AS last_end
    FROM members m
    JOIN stream_segments g ON g.stream_id = m.stream_id AND g.session_id = m.session_id
)
INSERT INTO broadcast_lifecycle_outbox (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, payload)
SELECT $2, st.round_id, $3, 2, COALESCE(sg.last_end, d.last_ended), st.ingest_stream_id,
       CASE WHEN sg.first_seq IS NULL THEN '{}'::jsonb
            ELSE pg_catalog.jsonb_build_object('segmentRange',
                   pg_catalog.jsonb_build_object('firstSeq', sg.first_seq, 'lastSeq', sg.last_seq)) END
  FROM started st CROSS JOIN done d CROSS JOIN segs sg
ON CONFLICT (event_id) DO NOTHING`

// EnqueueEndedIfComplete 는 회차를 닫거나 회차 세션을 ended 로 바꾸는 트랜잭션 끝에서 부른다(15.2 완결 평가 규칙 —
// CloseRound · tryCloseRound · 4D round_split · MarkChainEnded). 「닫힘 ∧ 전원 ended」 이고 started 행이 살아 있으면
// ended 행을 넣고 참을 돌려준다. 겹쳐 불려도 행은 하나다. 게이트가 꺼져 있으면 문장을 보내지 않는다. started 를 낸
// 회차만 ended 를 내므로 허용 목록은 started 쪽에서 이미 걸렀다.
func EnqueueEndedIfComplete(ctx context.Context, tx pgx.Tx, g Gate, round string) (bool, error) {
	if round == "" || !g.Enabled() {
		return false, nil
	}
	tag, err := tx.Exec(ctx, insertEndedSQL, StartedEventID(round), EndedEventID(round), EventEnded)
	if err != nil {
		return false, fmt.Errorf("broadcast: ended outbox 넣기(회차 %s): %w", round, err)
	}
	if tag.RowsAffected() == 0 {
		return false, nil
	}
	g.logger().Info("lifecycle_enqueued", "event_type", EventEnded, "round", round)
	return true, nil
}
