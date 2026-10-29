package main

// 계약9 outbox 단언(POK-196 M6-8 커밋 ④ — 계획 4.R D49 · D55 · D59 · D61 의 M6-8 몫). 앞 몫(M6-3a · M6-3b)은 함수 칸
// 가짜로 「어느 회차를 평가하는가」 를 봤다. 여기서는 조립이 끼우는 실제 칸(게이트로 묶은 broadcast 문장)으로 outbox 행이
// 회차마다 started 1 · ended 1 인지를 본다.

import (
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// allStreams 는 모든 스트림을 내는 게이트다.
func allStreams() broadcast.Gate {
	return broadcast.NewGate([]string{"*"}, slog.New(slog.DiscardHandler))
}

// outboxClose 는 운영과 같은 닫힘 수치(창 300초 · 여유 60초)다.
var outboxClose = index.CloseOptions{Window: 300 * time.Second, Slack: closeSlack, Log: slog.New(slog.DiscardHandler)}

// outboxCount 는 회차 round 의 event_type 행 수다.
func outboxCount(t *testing.T, pool *pgxpool.Pool, round, eventType string) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(),
		`SELECT pg_catalog.count(*) FROM broadcast_lifecycle_outbox WHERE round_id = $1 AND event_type = $2`, round, eventType).
		Scan(&n); err != nil {
		t.Fatalf("outbox 조회 실패: %v", err)
	}
	return n
}

// startedTotal 은 스트림의 started 행 수다.
func startedTotal(t *testing.T, pool *pgxpool.Pool, stream string) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), `SELECT pg_catalog.count(*) FROM broadcast_lifecycle_outbox
		WHERE ingest_stream_id = $1 AND event_type = $2`, stream, broadcast.EventStarted).Scan(&n); err != nil {
		t.Fatalf("outbox 조회 실패: %v", err)
	}
	return n
}

// publishedStarted 는 회차 round 의 started 를 이미 낸 행으로 심는다(ended 판정의 선행 조건).
func publishedStarted(t *testing.T, pool *pgxpool.Pool, round, stream string) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
INSERT INTO broadcast_lifecycle_outbox (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, state)
VALUES ($1, $2, $3, 1, $4, $5, 'published')`, broadcast.StartedEventID(round), round, broadcast.EventStarted, wireBase, stream); err != nil {
		t.Fatalf("started 픽스처 실패: %v", err)
	}
}

// seedOutboxSession 은 종료 국면 세션 한 행이다(dvr · vod 종국 · init 확정). inherits · marked 는 빈 값 · 영값이면 NULL.
func seedOutboxSession(t *testing.T, pool *pgxpool.Pool, id, stream, state, reason, round, inherits, vod string, started, marked time.Time) {
	t.Helper()
	var inh, mark any
	if inherits != "" {
		inh = inherits
	}
	if !marked.IsZero() {
		mark = marked
	}
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions (session_id, stream_id, started_at, state, end_reason, ending_at, ended_at, dvr_state, vod_state,
                             round_id, inherits_session, stop_marked_at, init_uploaded_at)
VALUES ($1, $2, $3, $4, NULLIF($5, ''), CASE WHEN $4 <> 'live' THEN $3::timestamptz + interval '1 minute' END,
        CASE WHEN $4 = 'ended' THEN $3::timestamptz + interval '2 minutes' END,
        CASE WHEN $4 = 'live' THEN 'open' ELSE 'frozen' END, $6, $7, $8, $9, $3)`,
		id, stream, started, state, reason, vod, round, inh, mark); err != nil {
		t.Fatalf("세션 픽스처 실패 %s: %v", id, err)
	}
}

// openVia 는 조립된 결정자로 결정 → 개시를 한 트랜잭션에서 돌린다(개시 갈래가 아니면 "").
func openVia(t *testing.T, pool *pgxpool.Pool, d index.SessionDecider, stream string, seq int64, start time.Time, durMS int32) string {
	t.Helper()
	ctx := context.Background()
	var id string
	err := pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
		dec, err := d.Decide(ctx, tx, index.SessionInput{StreamID: stream, Seq: seq, StartWallUTC: start, DurationMS: durMS,
			SessionSource: index.SessionSource{Op: index.SessionOpenOrCurrent}}, start)
		if err != nil || !dec.Opens {
			return err
		}
		id, err = d.Open(ctx, tx, dec, start)
		return err
	})
	if err != nil {
		t.Fatalf("결정 · 개시 실패 seq=%d: %v", seq, err)
	}
	if _, err := pool.Exec(ctx, `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id)
SELECT $1, $2::bigint, 0, $3, $4, 'k-' || $2::text, '/recordings/' || $2::text || '.mp4', 'uploaded', 1000, session_id
  FROM stream_sessions WHERE stream_id = $1 AND state = 'live'`, stream, seq, start, durMS); err != nil {
		t.Fatalf("조각 픽스처 실패 seq=%d: %v", seq, err)
	}
	return id
}

// seedOutboxSegment 는 세션의 4초 조각 한 행이다.
func seedOutboxSegment(t *testing.T, pool *pgxpool.Pool, stream, sessionID string, seq int64, start time.Time) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id)
VALUES ($1, $2, 0, $3, 4000, $4, $5, 'uploaded', 1000, $6)`,
		stream, seq, start, "k-"+sessionID, "/recordings/"+sessionID+".mp4", sessionID); err != nil {
		t.Fatalf("조각 픽스처 실패 %s: %v", sessionID, err)
	}
}

// D49 — TD 분할 · init 불일치 재접속을 거친 회차 하나에 started 1 이고, 전원 ended 뒤 회차가 닫히면 ended 1 이다.
// 변이 = 레지스트리가 세션마다 started 를 넣음 → started 3 으로 FAIL.
func TestRoundWithSplitAndReconnectEnqueuesOneStartedOneEndedD49(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	gate := allStreams()
	d := newSessionDecider(time.Second, 30*time.Second, 300*time.Second, gate, slog.New(slog.DiscardHandler))
	const s = "d49"
	first := openVia(t, pool, d, s, 1, wireBase, 4000)
	split := openVia(t, pool, d, s, 2, wireBase.Add(4*time.Second), 9000)
	if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET state='ending', ending_at=now(), end_reason='init_mismatch' WHERE session_id=$1`, split); err != nil {
		t.Fatalf("init 불일치 종료 흉내 실패: %v", err)
	}
	joined := openVia(t, pool, d, s, 3, wireBase.Add(20*time.Second), 4000)
	if first == "" || split == "" || joined == "" {
		t.Fatalf("개시가 서지 않았다 (%q, %q, %q)", first, split, joined)
	}
	if n := startedTotal(t, pool, s); n != 1 || outboxCount(t, pool, first, broadcast.EventStarted) != 1 {
		t.Fatalf("started %d 행, want 회차 %s 하나", n, first)
	}

	if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET state='ended', ending_at=COALESCE(ending_at, now()), ended_at=now(),
		end_reason=COALESCE(end_reason, 'offline') WHERE stream_id=$1`, s); err != nil {
		t.Fatalf("phase 4 흉내 실패: %v", err)
	}
	fa := finalizeAssembly{close: outboxClose, enqueue: newEndedEnqueuer(gate)}
	if err := pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error { return fa.closeRound()(ctx, tx, first) }); err != nil {
		t.Fatalf("회차 닫힘 실패: %v", err)
	}
	if n := outboxCount(t, pool, first, broadcast.EventEnded); n != 1 {
		t.Fatalf("ended %d 행, want 1", n)
	}
}

// D55 넷째 갈래 뒤 몫 — T 가 ended 인 회차 R 에 멈춘 뒤 송출 B 가 붙은 다음 4D 가 닿음(round_split). 분리 트랜잭션의 완결
// 평가로 R ended 1행 · 새 회차 B started 1행. 변이 = 분리 tx 의 완결 호출 제거 → R ended 0 으로 FAIL.
func TestRoundSplitAfterEndedTailEnqueuesROutboxD55(t *testing.T) {
	pool := newWireTestPool(t)
	gate := allStreams()
	const s = "d55-split"
	stop := wireBase
	seedOutboxSession(t, pool, "S-T", s, "ended", "offline", "S-R", "", "frozen", stop.Add(-time.Hour), time.Time{})
	seedOutboxSegment(t, pool, s, "S-T", 1, stop.Add(-5*time.Second))
	seedOutboxSession(t, pool, "S-B", s, "live", "", "S-R", "", "none", stop.Add(20*time.Second), time.Time{})
	publishedStarted(t, pool, "S-R", s)
	fa := finalizeAssembly{close: outboxClose, enqueue: newEndedEnqueuer(gate)}
	o := newIntentOptions(slog.New(slog.DiscardHandler), fa.closeRound(), startedEnqueuer(gate))

	res, err := session.ApplyIntentionalEnd(context.Background(), pool,
		session.IntentionalEnd{StreamID: s, StoppedAt: stop, ConnStartedAt: stop.Add(-time.Hour)}, o)

	if err != nil || res.Effect != "round_split" {
		t.Fatalf("결과 = (%+v, %v), want round_split", res, err)
	}
	if n := outboxCount(t, pool, "S-R", broadcast.EventEnded); n != 1 {
		t.Errorf("R ended %d 행, want 1", n)
	}
	if n := outboxCount(t, pool, "S-B", broadcast.EventStarted); n != 1 {
		t.Errorf("B started %d 행, want 1", n)
	}
}

// D55 다섯째 갈래 뒤 몫 — 동결 중 사슬이 늦은 분리로 회차 B 로 통째로 옮겨졌다. phase 4 는 다시 읽은 회차 B 로 완결을
// 평가해 B ended 1행이다. 변이 = Chain 의 RoundID(R)로 평가 → B ended 0 으로 FAIL.
func TestPhaseFourOnMovedChainEnqueuesNewRoundEndedD55(t *testing.T) {
	pool := newWireTestPool(t)
	const s = "d55-move"
	seedOutboxSession(t, pool, "X", s, "ending", "intentional", "B", "", "frozen", wireBase, time.Time{})
	publishedStarted(t, pool, "B", s)
	c := index.Chain{StreamID: s, RoundID: "R", Head: "X", Tail: "X", SessionIDs: []string{"X"}}

	if n, err := index.MarkChainEnded(context.Background(), pool, c, outboxClose, newEndedEnqueuer(allStreams())); err != nil || n != 1 {
		t.Fatalf("MarkChainEnded = %d err %v, want 1", n, err)
	}
	if n := outboxCount(t, pool, "B", broadcast.EventEnded); n != 1 {
		t.Fatalf("B ended %d 행, want 1", n)
	}
}

// D59 — init 불일치 꼬리 사슬이 먼저 ended 가 됐고 후속이 없다. 회차 스캔이 361초 뒤 그 회차를 닫으며 ended 1행이다.
// 변이 = 닫힘 조건을 최신 세션 ending 만으로 → 스캔에 안 잡혀 FAIL.
func TestRoundScanClosesEndedTailRoundAndEnqueuesD59(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	const s = "d59"
	seedOutboxSession(t, pool, "A", s, "ended", "init_mismatch", "A", "", "frozen", wireBase, time.Time{})
	publishedStarted(t, pool, "A", s)

	rounds, err := index.ListClosableRounds(ctx, pool, 50, outboxClose)
	if err != nil || len(rounds) != 1 || rounds[0].RoundID != "A" {
		t.Fatalf("회차 스캔 = %+v err %v, want [A]", rounds, err)
	}
	if closed, err := index.CloseRound(ctx, pool, rounds[0], outboxClose, newEndedEnqueuer(allStreams())); err != nil || !closed {
		t.Fatalf("CloseRound = %v err %v", closed, err)
	}
	if n := outboxCount(t, pool, "A", broadcast.EventEnded); n != 1 {
		t.Fatalf("ended %d 행, want 1", n)
	}
}

// D61 — 늦은 round_split 뒤 A 를 계승하는 B 는 다른 회차다. 사슬 간선이 같은 round_id 만이라 A 사슬은 B 송출 끝을
// 기다리지 않고 닫혀 phase 4 에 닿고, R ended 1행이다. 변이 = 간선 CTE 의 round_id 조건 제거 → A 가 닫히지 않아 FAIL.
func TestChainAcrossRoundEdgeClosesAndEnqueuesRD61(t *testing.T) {
	pool := newWireTestPool(t)
	ctx := context.Background()
	const s = "d61"
	now := time.Now()
	seedOutboxSession(t, pool, "A", s, "ending", "offline", "R", "", "none", now.Add(-time.Hour), now.Add(-time.Minute))
	seedOutboxSession(t, pool, "B", s, "live", "", "B", "A", "none", now.Add(-30*time.Second), time.Time{})
	publishedStarted(t, pool, "R", s)

	c, ok, err := index.CloseChain(ctx, pool, "A", outboxClose)
	if err != nil || !ok {
		t.Fatalf("CloseChain(A) = ok %v err %v, want 닫힘 — 회차를 가로지르는 계승이 후속으로 잡혔다", ok, err)
	}
	if _, err := pool.Exec(ctx, `UPDATE stream_sessions SET vod_state='frozen' WHERE session_id='A'`); err != nil {
		t.Fatalf("VOD 동결 흉내 실패: %v", err)
	}
	if n, err := index.MarkChainEnded(ctx, pool, c, outboxClose, newEndedEnqueuer(allStreams())); err != nil || n != 1 {
		t.Fatalf("MarkChainEnded = %d err %v, want 1", n, err)
	}
	if n := outboxCount(t, pool, "R", broadcast.EventEnded); n != 1 {
		t.Fatalf("R ended %d 행, want 1", n)
	}
}
