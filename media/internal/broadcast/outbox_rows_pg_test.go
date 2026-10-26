package broadcast

// 발행기 쪽 outbox 문장 실 PG 시험(POK-196 M6-8 커밋 ② · 설계 M6 15.5 · X4 · X5 · D36 · D63). 발행기(커밋 ③)는 이
// 문장들만 부른다.

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// insertRow 는 outbox 행을 바로 심는다(개시 · 완결 평가를 거치지 않는다).
func insertRow(t *testing.T, pool *pgxpool.Pool, round string, sequence int64, state string, nextAt time.Time, streamer *string) {
	t.Helper()
	eventID, eventType := StartedEventID(round), EventStarted
	if sequence == 2 {
		eventID, eventType = EndedEventID(round), EventEnded
	}
	_, err := pool.Exec(context.Background(), `
		INSERT INTO broadcast_lifecycle_outbox
			(event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, streamer_id, state, next_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
		eventID, round, eventType, sequence, base, ingest, streamer, state, nextAt)
	if err != nil {
		t.Fatalf("outbox 심기(%s): %v", eventID, err)
	}
}

func ptr(s string) *string { return &s }

func dueIDs(t *testing.T, pool *pgxpool.Pool, limit int) []string {
	t.Helper()
	rows, err := selectDue(context.Background(), pool, limit)
	if err != nil {
		t.Fatalf("selectDue: %v", err)
	}
	ids := make([]string, 0, len(rows))
	for _, r := range rows {
		ids = append(ids, r.eventID)
	}
	return ids
}

// D36 — 같은 회차의 낮은 sequence 가 published 가 아니면 ended 는 고르지 않는다(started 앞 ended 금지). started 가
// 나간 뒤에야 ended 가 대상이 된다.
// 잡는 결함: 보류 조건을 빼면 ended 가 먼저 나가 소비자가 자리표시 회차를 만든다.
func TestSelectDueHoldsEndedUntilStartedPublished(t *testing.T) {
	pool := newTestPool(t)
	past := time.Now().Add(-time.Minute)
	insertRow(t, pool, "S-A", 1, "pending", time.Now().Add(time.Hour), nil) // started — 아직 때가 안 됨
	insertRow(t, pool, "S-A", 2, "pending", past, nil)

	if ids := dueIDs(t, pool, 20); len(ids) != 0 {
		t.Fatalf("started 미발행 중 대상 = %v, want 없음", ids)
	}
	setState(t, pool, "S-A.started", "published", past)
	if ids := dueIDs(t, pool, 20); len(ids) != 1 || ids[0] != "S-A.ended" {
		t.Errorf("started 발행 뒤 대상 = %v, want [S-A.ended]", ids)
	}
}

// D63 — 선행 자격은 LIMIT 앞(WHERE)에서 거른다. 막힌 ended 30개가 쪽(20)을 채워도 뒤의 started 는 뽑힌다.
// 잡는 결함: 자격 검사를 LIMIT 뒤로 옮기면 쪽이 막힌 ended 로만 차 started 가 영원히 안 나간다.
func TestSelectDueQualifiesBeforeLimit(t *testing.T) {
	pool := newTestPool(t)
	old := time.Now().Add(-time.Hour)
	for i := range 30 {
		round := fmt.Sprintf("S-blocked-%02d", i)
		insertRow(t, pool, round, 1, "pending", time.Now().Add(time.Hour), nil)
		insertRow(t, pool, round, 2, "pending", old.Add(time.Duration(i)*time.Second), nil)
	}
	insertRow(t, pool, "S-fresh", 1, "pending", time.Now().Add(-time.Second), nil)

	ids := dueIDs(t, pool, 20)

	if len(ids) != 1 || ids[0] != "S-fresh.started" {
		t.Errorf("대상 = %v, want [S-fresh.started]", ids)
	}
}

// 고른 행은 next_at 순이고 때가 안 된 행 · pending 이 아닌 행은 빠진다. ended 의 streamerId 는 같은 회차 started 행의
// 값이다(D62 — 생성 때 복사하지 않는다).
func TestSelectDueReadsRowsInOrder(t *testing.T) {
	pool := newTestPool(t)
	now := time.Now()
	insertRow(t, pool, "S-A", 1, "published", now.Add(-time.Hour), ptr("42"))
	insertRow(t, pool, "S-A", 2, "pending", now.Add(-time.Minute), nil)
	insertRow(t, pool, "S-B", 1, "pending", now.Add(-2*time.Minute), nil)
	insertRow(t, pool, "S-C", 1, "abandoned", now.Add(-3*time.Minute), nil)
	insertRow(t, pool, "S-D", 1, "pending", now.Add(time.Minute), nil)

	rows, err := selectDue(context.Background(), pool, 20)
	if err != nil {
		t.Fatalf("selectDue: %v", err)
	}

	if len(rows) != 2 || rows[0].eventID != "S-B.started" || rows[1].eventID != "S-A.ended" {
		t.Fatalf("대상 = %+v, want [S-B.started S-A.ended]", rows)
	}
	ended := rows[1]
	if ended.streamerID != "42" || ended.round != "S-A" || ended.sequence != 2 || ended.eventType != EventEnded ||
		ended.ingest != ingest || !ended.occurredAt.Equal(base) {
		t.Errorf("ended 행 = %+v, want streamer 42 · S-A · 2 · %s · %s · %v", ended, EventEnded, ingest, base)
	}
	if rows[0].streamerID != "" {
		t.Errorf("started streamer = %q, want 빈 값(아직 resolve 전)", rows[0].streamerID)
	}
}

// started 를 버리면 같은 트랜잭션(문장 하나)에서 그 회차의 pending 행(ended)도 started_abandoned 로 버린다(X4 규칙 2).
// ended 를 버려도 started 는 그대로다.
func TestAbandonCascadesFromStarted(t *testing.T) {
	pool := newTestPool(t)
	past := time.Now().Add(-time.Minute)
	insertRow(t, pool, "S-A", 1, "pending", past, nil)
	insertRow(t, pool, "S-A", 2, "pending", past, nil)
	insertRow(t, pool, "S-B", 1, "published", past, ptr("7"))
	insertRow(t, pool, "S-B", 2, "pending", past, nil)

	if _, err := abandon(context.Background(), pool, outboxRow{eventID: "S-A.started", round: "S-A", sequence: 1},
		"streamer_invalid"); err != nil {
		t.Fatalf("abandon(started): %v", err)
	}
	if _, err := abandon(context.Background(), pool, outboxRow{eventID: "S-B.ended", round: "S-B", sequence: 2},
		"streamer_missing"); err != nil {
		t.Fatalf("abandon(ended): %v", err)
	}

	for id, want := range map[string][2]string{
		"S-A.started": {"abandoned", "streamer_invalid"},
		"S-A.ended":   {"abandoned", "started_abandoned"},
		"S-B.started": {"published", ""},
		"S-B.ended":   {"abandoned", "streamer_missing"},
	} {
		r, _ := readOutbox(t, pool, id)
		if r.state != want[0] || r.lastError != want[1] {
			t.Errorf("%s 상태 · 사유 = %s · %q, want %s · %q", id, r.state, r.lastError, want[0], want[1])
		}
	}
}

// 발행 성공 · 재시도 · streamerId 기록 · 정리 문장. 재시도는 시도 횟수를 올리고 next_at 을 미룬다. 정리는 ended 가
// 발행된 지 7일이 지난 회차의 행만 지운다(15.5 규칙 5).
func TestRowTransitions(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	past := time.Now().Add(-time.Minute)
	insertRow(t, pool, "S-A", 1, "pending", past, nil)

	if err := recordStreamer(ctx, pool, "S-A.started", "42"); err != nil {
		t.Fatalf("recordStreamer: %v", err)
	}
	if err := markRetry(ctx, pool, "S-A.started", 4*time.Second, "sns: timeout"); err != nil {
		t.Fatalf("markRetry: %v", err)
	}
	r, _ := readOutbox(t, pool, "S-A.started")
	if r.streamerID == nil || *r.streamerID != "42" || r.attempts != 1 || r.lastError != "sns: timeout" || r.state != "pending" {
		t.Errorf("재시도 뒤 = %+v, want streamer 42 · attempts 1 · sns: timeout · pending", r)
	}
	if ids := dueIDs(t, pool, 20); len(ids) != 0 {
		t.Errorf("재시도 직후 대상 = %v, want 없음(next_at 미룸)", ids)
	}

	if err := markPublished(ctx, pool, "S-A.started"); err != nil {
		t.Fatalf("markPublished: %v", err)
	}
	r, _ = readOutbox(t, pool, "S-A.started")
	if r.state != "published" || !r.published || r.attempts != 2 {
		t.Errorf("발행 뒤 = %+v, want published · published_at · attempts 2", r)
	}

	insertRow(t, pool, "S-old", 1, "published", past, ptr("1"))
	insertRow(t, pool, "S-old", 2, "published", past, nil)
	insertRow(t, pool, "S-new", 1, "published", past, ptr("1"))
	insertRow(t, pool, "S-new", 2, "published", past, nil)
	if _, err := pool.Exec(ctx, `UPDATE broadcast_lifecycle_outbox SET published_at = pg_catalog.now() - interval '8 days'
	                              WHERE round_id = 'S-old'`); err != nil {
		t.Fatalf("published_at 되돌리기: %v", err)
	}
	n, err := deleteSettled(ctx, pool, 7*24*time.Hour)
	if err != nil {
		t.Fatalf("deleteSettled: %v", err)
	}
	if n != 2 {
		t.Errorf("지운 행 = %d, want 2(S-old 둘)", n)
	}
	if _, ok := readOutbox(t, pool, "S-new.ended"); !ok {
		t.Error("S-new.ended 가 지워졌다 — 7일 안")
	}
	if _, ok := readOutbox(t, pool, "S-A.started"); !ok {
		t.Error("S-A.started 가 지워졌다 — ended 없음")
	}
}
