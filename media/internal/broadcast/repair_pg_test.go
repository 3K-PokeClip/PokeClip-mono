package broadcast

// 발행기 보정 실 PG 시험(sgd 설계 2-1 · 4-7 · 8절 J10′). 닫는 쪽이 ended 를 넣지 못한 완결 회차(꺼진 게이트 · 옛 판 ·
// 권한 판정 거짓)를 발행기가 tick 머리에서 거두는지 본다. 보정 메서드는 직접 부르지 않고 늘 tick · Run 으로 돈다.
// 시각은 전부 DB now() 기준 상대값이다 — 고정 시각(base)은 보정 창(58일)을 지나면 후보에서 빠져 결과가 날짜에 따라 갈린다.

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// repairKind 는 보정 시험 회차의 장부 꼴이다.
type repairKind string

const (
	roundComplete  repairKind = "완결"          // 닫힘 ∧ 전원 ended — 보정 대상
	roundOpen      repairKind = "닫힘_없음"       // 전원 ended · round_closed_at 없음
	roundEnding    repairKind = "ended_아닌_세션" // 닫힘 · 합류 세션이 ending
	roundNoSession repairKind = "세션_0"        // started 행만 있다
)

// repairRound 는 보정 시험 회차 하나다. 회차 열쇠는 머리 세션 id 이고, stream_id 는 회차마다 따로다(repairStream).
type repairRound struct {
	id      string
	kind    repairKind
	start   time.Time // started occurred_at = 머리 세션 started_at = started 행 created_at
	started string    // started 행 state — 빈 값이면 published
}

// repairLastEnd 는 seedRepairRound 가 심는 회차의 마지막 조각 끝(start 기준)이다.
const repairLastEnd = 64 * time.Second

func repairStream(round string) string { return "rp-" + round }

// seedRepairRound 는 회차 r 을 심는다 — started 행(streamer 42)과, 세션이 있는 꼴이면 머리 세션(조각 seq 1 · 2)과
// 합류 세션(조각 seq 3). 마지막 조각 끝은 start + repairLastEnd 다.
func seedRepairRound(t *testing.T, pool *pgxpool.Pool, r repairRound) {
	t.Helper()
	stream, state := repairStream(r.id), r.started
	if state == "" {
		state = "published"
	}
	mustExec(t, pool, `
		INSERT INTO broadcast_lifecycle_outbox
		       (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, streamer_id, state, created_at,
		        published_at)
		VALUES ($1, $2, $3, 1, $4, $5, '42', $6, $4, CASE WHEN $6 = 'published' THEN $4::timestamptz END)`,
		StartedEventID(r.id), r.id, EventStarted, r.start, stream, state)
	if r.kind == roundNoSession {
		return
	}
	closed, joined := r.kind != roundOpen, "ended"
	if r.kind == roundEnding {
		joined = "ending"
	}
	seedRepairSession(t, pool, stream, r.id, r.id, "ended", r.start, closed)
	seedRepairSession(t, pool, stream, r.id+"-b", r.id, joined, r.start.Add(time.Minute), closed)
	seedRepairSegment(t, pool, stream, r.id, 1, r.start)
	seedRepairSegment(t, pool, stream, r.id, 2, r.start.Add(4*time.Second))
	seedRepairSegment(t, pool, stream, r.id+"-b", 3, r.start.Add(time.Minute))
}

func seedRepairSession(t *testing.T, pool *pgxpool.Pool, stream, id, round, state string, started time.Time, closed bool) {
	t.Helper()
	mustExec(t, pool, `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, round_id, ending_at, ended_at, round_closed_at)
		VALUES ($1, $2, $3, $4, $5, $3::timestamptz + interval '70 seconds',
		        CASE WHEN $4 = 'ended' THEN $3::timestamptz + interval '80 seconds' END,
		        CASE WHEN $6 THEN $3::timestamptz + interval '10 minutes' END)`,
		id, stream, started, state, round, closed)
}

func seedRepairSegment(t *testing.T, pool *pgxpool.Pool, stream, sessionID string, seq int64, start time.Time) {
	t.Helper()
	mustExec(t, pool, `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, session_id)
		VALUES ($1, $2, $3, $4, 4000, $5, $6)`,
		stream, seq, seq*4000, start, fmt.Sprintf("%s/%d", stream, seq), sessionID)
}

func mustExec(t *testing.T, pool *pgxpool.Pool, sql string, args ...any) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), sql, args...); err != nil {
		t.Fatalf("픽스처 문장 실패: %v", err)
	}
}

// dbNow 는 DB 시계다 — 보정 창 술어가 pg_catalog.now() 로 재므로 픽스처 시각도 이 값에서 끌어 온다.
func dbNow(t *testing.T, pool *pgxpool.Pool) time.Time {
	t.Helper()
	var now time.Time
	if err := pool.QueryRow(context.Background(), "SELECT pg_catalog.now()").Scan(&now); err != nil {
		t.Fatalf("DB 시계 읽기: %v", err)
	}
	return now
}

// newRepairPublisher 는 발행 · 신호를 잡는 발행기다. resolve 는 없다 — 보정 시험의 started 는 streamer 가 이미 있다.
func newRepairPublisher(t *testing.T, pool *pgxpool.Pool) (*Publisher, *fakeSNS, *signals) {
	t.Helper()
	sns, sig := &fakeSNS{}, &signals{}
	return newPublisher(t, pool, sns, nil, sig), sns, sig
}

func repairedCount(sig *signals) int { return sig.count(slog.LevelWarn, "lifecycle_ended_repaired") }

// hasEnded 는 회차 round 의 ended 행이 있는지다.
func hasEnded(t *testing.T, pool *pgxpool.Pool, round string) bool {
	t.Helper()
	_, ok := readOutbox(t, pool, EndedEventID(round))
	return ok
}

// requireRepairedEnded 는 회차 round 의 보정 ended 가 원본 장부 값(마지막 조각 끝 · seq 1–3)인지 본다.
func requireRepairedEnded(t *testing.T, pool *pgxpool.Pool, round string, start time.Time) outboxRecord {
	t.Helper()
	r, ok := readOutbox(t, pool, EndedEventID(round))
	if !ok {
		t.Fatalf("회차 %s 의 ended 행이 없다 — 보정이 거두지 않았다", round)
	}
	if want := start.Add(repairLastEnd); r.sequence != 2 || r.eventType != EventEnded || !r.occurredAt.Equal(want) {
		t.Errorf("ended 행 = %+v, want sequence 2 · %s · occurred_at %v(마지막 조각 끝)", r, EventEnded, want)
	}
	var payload struct {
		SegmentRange struct{ FirstSeq, LastSeq int64 } `json:"segmentRange"`
	}
	if err := json.Unmarshal([]byte(r.payload), &payload); err != nil {
		t.Fatalf("payload 해석(%s): %v", r.payload, err)
	}
	if payload.SegmentRange.FirstSeq != 1 || payload.SegmentRange.LastSeq != 3 {
		t.Errorf("segmentRange = %+v, want 1–3(회차 전체)", payload.SegmentRange)
	}
	return r
}

// J10′(a) — 닫는 쪽이 ended 를 넣지 못한 완결 회차(started published · ended 없음)를 tick 하나가 보정해 같은 tick 에
// 낸다. 값은 닫는 쪽 문장(insertEndedSQL)과 같다 — occurred_at 은 마지막 조각 끝, segmentRange 는 회차 전체 seq 다.
// 잡는 결함: 보정이 없으면 그 회차의 ended 가 영영 없다(P0 · V1).
func TestRepairEnqueuesMissingEndedAndPublishesIt(t *testing.T) {
	pool := newTestPool(t)
	start := dbNow(t, pool).Add(-time.Hour)
	seedRepairRound(t, pool, repairRound{id: "R-A", kind: roundComplete, start: start})
	p, sns, sig := newRepairPublisher(t, pool)

	p.tick(context.Background())

	if r := requireRepairedEnded(t, pool, "R-A", start); r.state != "published" {
		t.Errorf("ended state = %s, want published(같은 tick 발행)", r.state)
	}
	if msgs := sns.sent(); len(msgs) != 1 || decode(t, msgs[0]).EventType != EventEnded {
		t.Errorf("SNS 편지 %d 통, want ended 한 통", len(msgs))
	}
	if n := sig.count(slog.LevelWarn, "lifecycle_ended_repaired", "round", "R-A"); n != 1 {
		t.Errorf("WARN lifecycle_ended_repaired round=R-A %d 줄, want 1", n)
	}
	if n := sig.count(slog.LevelWarn, "lifecycle_store_failed"); n != 0 {
		t.Errorf("lifecycle_store_failed %d 줄, want 0", n)
	}
}

// J10′(b) — 완결 아닌 후보가 쪽 하나(repairBatch)보다 많이 앞에 있어도 완결 회차만 빠짐없이 거둔다. 완결 조건(닫힘 ∧
// 전원 ended ∧ 세션 ≥ 1)을 고르기 WHERE 에서 미리 거르므로 완결 아닌 회차가 쪽을 차지하지 않는다.
// 잡는 결함: 사전 거름이 없으면 앞의 완결 아닌 회차가 쪽을 채워 뒤의 완결 회차가 영영 밀린다(P20 · V14).
func TestRepairPrefiltersIncompleteRoundsAhead(t *testing.T) {
	pool := newTestPool(t)
	now := dbNow(t, pool)
	incomplete := map[string]repairKind{}
	kinds := []repairKind{roundOpen, roundEnding, roundNoSession}
	for i := 0; i <= repairBatch; i++ {
		id := fmt.Sprintf("R-I%02d", i)
		incomplete[id] = kinds[i%len(kinds)]
		seedRepairRound(t, pool, repairRound{id: id, kind: incomplete[id], start: now.Add(-3*time.Hour + time.Duration(i)*time.Minute)})
	}
	complete := []string{"R-C1", "R-C2"}
	for i, id := range complete {
		seedRepairRound(t, pool, repairRound{id: id, kind: roundComplete, start: now.Add(-time.Hour + time.Duration(i)*time.Minute)})
	}
	p, _, sig := newRepairPublisher(t, pool)

	p.tick(context.Background())

	for i, id := range complete {
		requireRepairedEnded(t, pool, id, now.Add(-time.Hour+time.Duration(i)*time.Minute))
	}
	for id, kind := range incomplete {
		if hasEnded(t, pool, id) {
			t.Errorf("완결 아닌 회차 %s(%s) 에 ended 가 생겼다", id, kind)
		}
	}
	if n := repairedCount(sig); n != len(complete) {
		t.Errorf("WARN lifecycle_ended_repaired %d 줄, want %d", n, len(complete))
	}
}

// J10′(c) — started 가 abandoned 인 완결 회차에는 ended 를 넣지 않는다. 고른 뒤 넣기 전에 버려져도 같다 — 넣기는
// 닫는 쪽 문장(insertEndedSQL)이라 started 상태를 다시 본다.
// 잡는 결함: 보정이 started 상태를 보지 않는 INSERT 를 쓰면 버린 회차에 ended 가 생긴다(P21 · V15).
func TestRepairSkipsAbandonedStarted(t *testing.T) {
	t.Run("고르기_전_버림", func(t *testing.T) {
		pool := newTestPool(t)
		seedRepairRound(t, pool, repairRound{id: "R-X", kind: roundComplete, start: dbNow(t, pool).Add(-time.Hour),
			started: "abandoned"})
		p, _, sig := newRepairPublisher(t, pool)

		p.tick(context.Background())

		if hasEnded(t, pool, "R-X") || repairedCount(sig) != 0 {
			t.Errorf("ended 있음 %v · repaired %d, want 없음 · 0", hasEnded(t, pool, "R-X"), repairedCount(sig))
		}
	})
	t.Run("고르기_뒤_버림", func(t *testing.T) {
		pool := newTestPool(t)
		ctx := context.Background()
		seedRepairRound(t, pool, repairRound{id: "R-X", kind: roundComplete, start: dbNow(t, pool).Add(-time.Hour)})
		rounds, err := selectMissingEnded(ctx, pool, repairBatch)
		if err != nil || len(rounds) != 1 || rounds[0] != "R-X" {
			t.Fatalf("selectMissingEnded = %v err %v, want [R-X]", rounds, err)
		}
		mustExec(t, pool, "UPDATE broadcast_lifecycle_outbox SET state = 'abandoned' WHERE event_id = $1",
			StartedEventID("R-X"))

		ok, err := insertMissingEnded(ctx, pool, "R-X")

		if ok || err != nil || hasEnded(t, pool, "R-X") {
			t.Errorf("insertMissingEnded = (%v, %v) · ended 있음 %v, want (false, nil) · 없음", ok, err, hasEnded(t, pool, "R-X"))
		}
	})
}

// J10′(d) — 발행기 기동 뒤 첫 tick 은 보정 주기를 기다리지 않는다(lastRepair 영값).
// 잡는 결함: 첫 보정을 repairEvery 뒤로 미루면 짧게 떴다 지는 발행기는 보정을 한 번도 못 한다(P22 · V16).
func TestRepairRunsOnFirstTickBeforeInterval(t *testing.T) {
	pool := newTestPool(t)
	start := dbNow(t, pool).Add(-time.Hour)
	seedRepairRound(t, pool, repairRound{id: "R-D", kind: roundComplete, start: start})
	p, _, _ := newRepairPublisher(t, pool)
	p.repairEvery = time.Hour

	p.tick(context.Background())

	requireRepairedEnded(t, pool, "R-D", start)
}

// J10′(e) — 다른 트랜잭션이 맨 앞 후보의 started 행을 FOR UPDATE 로 쥔 동안 보정은 repairTimeout 안에 돌아오고 같은
// tick 의 발행은 다른 행을 낸다. 실패한 시도도 주기를 쓴다 — 둘째 tick 은 보정 문장을 보내지 않는다. 잠금이 풀리면 다음
// 보정이 그 회차와 뒤 후보를 거둔다.
// 잡는 결함: 시한이 없으면 tick 이 잠금 내내 선다(P23 · V17). 성공 뒤에만 주기를 갱신하면 잠금 동안 tick 마다 시한만큼
// 보정이 돌아 발행이 느려지고 WARN 이 쏟아진다(V28).
func TestRepairTimeoutKeepsTickMovingAndWaitsForNextCycle(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	now := dbNow(t, pool)
	seedRepairRound(t, pool, repairRound{id: "R-E1", kind: roundComplete, start: now.Add(-2 * time.Hour)})
	seedRepairRound(t, pool, repairRound{id: "R-E2", kind: roundComplete, start: now.Add(-time.Hour)})
	insertRow(t, pool, "S-O", 1, "pending", base, ptr("42"))
	counter := &statementCounter{}
	p, sns, sig := newRepairPublisher(t, pgtest.TracedPool(ctx, t, pool, counter))
	p.repairEvery, p.repairTimeout = time.Hour, 200*time.Millisecond
	lock, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("Begin: %v", err)
	}
	t.Cleanup(func() { _ = lock.Rollback(ctx) })
	if _, err := lock.Exec(ctx, "SELECT 1 FROM broadcast_lifecycle_outbox WHERE event_id = $1 FOR UPDATE",
		StartedEventID("R-E1")); err != nil {
		t.Fatalf("started 행 잠금: %v", err)
	}

	tickWithin(t, p, p.repairTimeout+time.Second) // 설정 시한 + 발행 한 행 몫 — 시한을 설정값보다 길게 쓰면 넘는다

	if msgs := sns.sent(); len(msgs) != 1 || msgs[0].DeduplicationID != StartedEventID("S-O") {
		t.Errorf("SNS 편지 %d 통, want S-O.started 한 통 — 보정 잠금 대기가 발행을 막았다", len(msgs))
	}
	if n := sig.count(slog.LevelWarn, "lifecycle_store_failed", "stage", "repair"); n != 1 {
		t.Errorf("lifecycle_store_failed stage=repair %d 줄, want 1", n)
	}
	if hasEnded(t, pool, "R-E1") || hasEnded(t, pool, "R-E2") {
		t.Error("잠금 중에 ended 가 생겼다")
	}
	tickWithin(t, p, 5*time.Second)
	if n := counter.exactly(missingEndedSQL); n != 1 {
		t.Errorf("보정 고르기 문장 %d 번, want 1 — 실패한 시도 뒤 주기 안의 둘째 tick 이 다시 보정했다", n)
	}

	if err := lock.Rollback(ctx); err != nil {
		t.Fatalf("잠금 풀기: %v", err)
	}
	// 셋째 tick 은 시한이 아니라 회수를 본다 — 첫 tick 용 200ms 를 그대로 두면 부하에서 고르기 · 넣기 둘이 시한을 넘겨
	// 그 주기를 멈춘다(설계대로의 동작이라 시험만 고친다).
	p.repairEvery, p.repairTimeout = 0, 5*time.Second
	tickWithin(t, p, 5*time.Second)
	requireRepairedEnded(t, pool, "R-E1", now.Add(-2*time.Hour))
	requireRepairedEnded(t, pool, "R-E2", now.Add(-time.Hour))
}

// tickWithin 은 tick 하나가 limit 안에 돌아오는지 본다. 넘으면 실패한다 — 선 tick 은 시험 정리(잠금 해제)가 풀어 준다.
func tickWithin(t *testing.T, p *Publisher, limit time.Duration) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		defer close(done)
		p.tick(context.Background())
	}()
	select {
	case <-done:
	case <-time.After(limit):
		t.Fatalf("tick 이 %v 안에 돌아오지 않았다 — 보정이 잠금 대기에 섰다", limit)
	}
}

// J10′(f) — 쓰기 가능한 켜진 닫는 쪽이 닫힘 트랜잭션에서 ended 를 넣은 회차는 보정 대상이 아니다.
// 잡는 결함: 보정 술어가 「ended 행 없음」 을 보지 않거나 닫는 쪽 적재가 조용히 빠지면 정상 경로에서 repaired 가
// 나온다(V26 — 닫는 쪽이 늘 (false, nil) 이면 보정이 거둬 repaired 1).
func TestRepairLeavesRoundsClosedWithEnded(t *testing.T) {
	pool := newTestPool(t)
	seedRepairRound(t, pool, repairRound{id: "R-F", kind: roundComplete, start: dbNow(t, pool).Add(-time.Hour)})
	t.Logf("닫는 쪽 EnqueueEndedIfComplete = %v", enqueueEnded(t, pool, openGate(), "R-F"))
	p, _, sig := newRepairPublisher(t, pool)

	p.tick(context.Background())

	if n := repairedCount(sig); n != 0 {
		t.Errorf("WARN lifecycle_ended_repaired %d 줄, want 0 — 닫는 쪽이 이미 넣은 회차를 보정했다", n)
	}
	if !hasEnded(t, pool, "R-F") {
		t.Error("ended 행이 없다")
	}
}

// J10′(g) — 두 발행기가 같은 후보를 고른 뒤 차례로 넣는다. 첫째는 (true, nil), 둘째는 (false, nil) 이고 ended 는 한
// 행이다. 그 뒤의 tick 은 오류도 보정도 없다.
// 잡는 결함: 넣기에 ON CONFLICT 가 없으면 둘째가 23505 로 실패해 store_failed 가 쌓인다(P15 · V24).
func TestRepairRaceBetweenPublishersKeepsOneEnded(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	seedRepairRound(t, pool, repairRound{id: "R-G", kind: roundComplete, start: dbNow(t, pool).Add(-time.Hour)})
	first, err1 := selectMissingEnded(ctx, pool, repairBatch)
	second, err2 := selectMissingEnded(ctx, pool, repairBatch)
	if err1 != nil || err2 != nil || len(first) != 1 || len(second) != 1 || first[0] != "R-G" || second[0] != "R-G" {
		t.Fatalf("고르기 = %v / %v (err %v / %v), want 둘 다 [R-G]", first, second, err1, err2)
	}

	ok1, err1 := insertMissingEnded(ctx, pool, first[0])
	ok2, err2 := insertMissingEnded(ctx, pool, second[0])

	if !ok1 || err1 != nil || ok2 || err2 != nil {
		t.Errorf("넣기 = (%v, %v) · (%v, %v), want (true, nil) · (false, nil)", ok1, err1, ok2, err2)
	}
	var n int
	if err := pool.QueryRow(ctx, "SELECT pg_catalog.count(*) FROM broadcast_lifecycle_outbox WHERE round_id = 'R-G' AND sequence = 2").
		Scan(&n); err != nil || n != 1 {
		t.Errorf("ended 행 %d (err %v), want 1", n, err)
	}
	p, _, sig := newRepairPublisher(t, pool)
	p.tick(ctx)
	if repairedCount(sig) != 0 || sig.count(slog.LevelWarn, "lifecycle_store_failed") != 0 {
		t.Errorf("뒤 tick 의 repaired %d · store_failed %d, want 0 · 0", repairedCount(sig),
			sig.count(slog.LevelWarn, "lifecycle_store_failed"))
	}
}

// J10′(h) — 후보가 2×repairBatch+1 개면 주기마다 쪽 하나씩 20 · 20 · 1 을 거두고 repaired 합이 후보 수다. 넣은 회차는
// 「ended 행 없음」 술어로 다음 고르기에서 빠지므로 커서 없이 앞으로 간다.
// 잡는 결함: 그 술어가 없으면 같은 첫 쪽을 영영 다시 골라 뒤 후보가 밀린다(P30 · V22).
func TestRepairAdvancesOnePagePerCycle(t *testing.T) {
	pool := newTestPool(t)
	now := dbNow(t, pool)
	total := 2*repairBatch + 1
	for i := 0; i < total; i++ {
		seedRepairRound(t, pool, repairRound{id: fmt.Sprintf("R-H%02d", i), kind: roundComplete,
			start: now.Add(-3*time.Hour + time.Duration(i)*time.Minute)})
	}
	p, _, sig := newRepairPublisher(t, pool)
	p.repairEvery = 0

	for i, want := range []int{repairBatch, 2 * repairBatch, total, total} {
		p.tick(context.Background())
		if n := repairedCount(sig); n != want {
			t.Fatalf("주기 %d 뒤 repaired 누계 %d, want %d", i+1, n, want)
		}
	}
	for i := 0; i < total; i++ {
		if id := fmt.Sprintf("R-H%02d", i); !hasEnded(t, pool, id) {
			t.Errorf("회차 %s 의 ended 가 없다", id)
		}
	}
}

// J10′(i) — 보정은 tick 머리라 보정한 ended 가 같은 tick 의 발행으로 나간다. Interval 을 크게 줘 다음 tick 이 없게 한다.
// 잡는 결함: 보정을 tick 끝에 두면 ended 가 한 주기(Interval) 늦게 나간다(P32 · V23).
func TestRepairedEndedPublishesInSameTick(t *testing.T) {
	pool := newTestPool(t)
	seedRepairRound(t, pool, repairRound{id: "R-I", kind: roundComplete, start: dbNow(t, pool).Add(-time.Hour)})
	sns, sig := &fakeSNS{}, &signals{}
	p, err := NewPublisher(pool, PublisherOptions{Publish: sns.publish, Log: slog.New(sig), Interval: time.Hour})
	if err != nil {
		t.Fatalf("NewPublisher: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- p.Run(ctx) }()

	deadline := time.Now().Add(5 * time.Second)
	for len(sns.sent()) == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	cancel()
	<-done

	if msgs := sns.sent(); len(msgs) != 1 || decode(t, msgs[0]).EventID != EndedEventID("R-I") {
		t.Fatalf("첫 tick 의 SNS 편지 %d 통, want R-I.ended 한 통", len(msgs))
	}
}

// J10′(j) — 보정 창은 만료가 지울 수 있는 회차와 서로 배타다. 만료가 마지막 조각만 지운 59일 전 회차는 후보가 아니다
// (보정하면 종료 시각 · seq 범위가 잘린 ended 가 나간다). 경계 두 점은 창 안 1시간 → 원본 장부 값으로 보정 · 창 밖
// 1시간 → 0 이다.
// 잡는 결함: 보정 창 술어가 없으면 만료가 조각 일부를 지운 회차에 틀린 ended 가 나간다(P25 · P29 · V25).
func TestRepairHorizonExcludesExpiryReachableRounds(t *testing.T) {
	if limit := index.ExpiryRetention - index.ExpiryUntilSlack - 24*time.Hour; repairHorizon <= 0 || repairHorizon > limit {
		t.Fatalf("repairHorizon = %v, want 0 < h ≤ %v(만료 가능 영역과 하루 이상 떨어짐)", repairHorizon, limit)
	}
	pool := newTestPool(t)
	ctx := context.Background()
	now := dbNow(t, pool)
	seedRepairRound(t, pool, repairRound{id: "R-X", kind: roundComplete, start: now.Add(-(index.ExpiryRetention - index.ExpiryUntilSlack))})
	res, err := index.DeleteExpired(ctx, pool, repairStream("R-X"), []int64{3})
	if err != nil || res.RowsDeleted != 1 {
		t.Fatalf("DeleteExpired = %+v err %v, want 마지막 조각 1행", res, err)
	}
	inside := now.Add(-repairHorizon + time.Hour)
	seedRepairRound(t, pool, repairRound{id: "R-IN", kind: roundComplete, start: inside})
	seedRepairRound(t, pool, repairRound{id: "R-OUT", kind: roundComplete, start: now.Add(-repairHorizon - time.Hour)})
	p, _, sig := newRepairPublisher(t, pool)

	p.tick(ctx)

	requireRepairedEnded(t, pool, "R-IN", inside)
	for _, id := range []string{"R-X", "R-OUT"} {
		if hasEnded(t, pool, id) {
			t.Errorf("창 밖 회차 %s 에 ended 가 생겼다", id)
		}
	}
	if n := repairedCount(sig); n != 1 {
		t.Errorf("WARN lifecycle_ended_repaired %d 줄, want 1(R-IN)", n)
	}
}
