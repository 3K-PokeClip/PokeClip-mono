package broadcast

// outbox 문장 실 PG 시험(POK-196 M6-8 커밋 ② · 설계 M6 15.4 · 15.5 · D36 · D37 · D44 · D45 · D63).
//
// 장부 행(stream_sessions · stream_segments)은 픽스처가 직접 심는다 — 회차 판정(registry)과 회차 닫힘(index)은 앞
// PR 의 몫이고, 여기서는 그 결과 위에서 outbox 문장만 잰다.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// TestMain 은 릴리스 게이트용 스위치다(session/registry_pg_test.go 와 같은 이디엄) — REQUIRE_PG=1 인데 PG_DSN 이
// 비면 실패한다. skip 은 성공으로 집계돼 outbox 문장이 한 줄도 검증되지 않은 실행이 녹색으로 보인다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr, "REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — outbox 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

var base = time.Date(2026, 10, 26, 10, 0, 0, 0, time.UTC)

// ingest 는 회차의 stream_id 다 — 토큰 꼴(26자 Crockford 대문자)이라 발행기가 resolve 에 그대로 묻는다.
const ingest = "01HZX3K9QW8E7RMNPA5BCDTFGV"

func newTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "broadcast", index.EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		_, err := pool.Exec(ctx,
			"TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions, stream_seq_floors, broadcast_lifecycle_outbox")
		return err
	})
}

// openGate 는 모든 스트림을 내는 게이트다.
func openGate() Gate { return NewGate([]string{"*"}, slog.New(slog.DiscardHandler)) }

// inTx 는 fn 을 트랜잭션 하나에서 돌리고 커밋한다.
func inTx(t *testing.T, pool *pgxpool.Pool, fn func(pgx.Tx) error) {
	t.Helper()
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("Begin: %v", err)
	}
	defer tx.Rollback(ctx)
	if err := fn(tx); err != nil {
		t.Fatalf("트랜잭션 안 호출: %v", err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("Commit: %v", err)
	}
}

func enqueueStarted(t *testing.T, pool *pgxpool.Pool, g Gate, round, ingestID string, at time.Time) {
	t.Helper()
	inTx(t, pool, func(tx pgx.Tx) error { return EnqueueStarted(context.Background(), tx, g, round, ingestID, at) })
}

func enqueueEnded(t *testing.T, pool *pgxpool.Pool, g Gate, round string) bool {
	t.Helper()
	var enqueued bool
	inTx(t, pool, func(tx pgx.Tx) error {
		var err error
		enqueued, err = EnqueueEndedIfComplete(context.Background(), tx, g, round)
		return err
	})
	return enqueued
}

// outboxRecord 는 시험이 읽는 outbox 행 전체다.
type outboxRecord struct {
	round, eventType, ingest, state, lastError string
	sequence                                   int64
	occurredAt                                 time.Time
	streamerID                                 *string
	payload                                    string
	attempts                                   int
	published                                  bool
}

func readOutbox(t *testing.T, pool *pgxpool.Pool, eventID string) (outboxRecord, bool) {
	t.Helper()
	var r outboxRecord
	var lastErr *string
	err := pool.QueryRow(context.Background(), `
		SELECT round_id, event_type, ingest_stream_id, state, last_error, sequence, occurred_at, streamer_id,
		       payload::text, attempts, published_at IS NOT NULL
		  FROM broadcast_lifecycle_outbox WHERE event_id = $1`, eventID).
		Scan(&r.round, &r.eventType, &r.ingest, &r.state, &lastErr, &r.sequence, &r.occurredAt, &r.streamerID,
			&r.payload, &r.attempts, &r.published)
	if errors.Is(err, pgx.ErrNoRows) {
		return r, false
	}
	if err != nil {
		t.Fatalf("outbox 읽기(%s): %v", eventID, err)
	}
	if lastErr != nil {
		r.lastError = *lastErr
	}
	return r, true
}

func countOutbox(t *testing.T, pool *pgxpool.Pool) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), "SELECT count(*) FROM broadcast_lifecycle_outbox").Scan(&n); err != nil {
		t.Fatalf("outbox 세기: %v", err)
	}
	return n
}

// session 은 픽스처가 심는 세션 행이다. closed 면 round_closed_at 을 쓴다(회차 전원 행에 같은 값 — 15.2).
type session struct {
	id, round, state string
	startedAt        time.Time
	closed           bool
}

func seedSession(t *testing.T, pool *pgxpool.Pool, s session) {
	t.Helper()
	var round *string
	if s.round != "" {
		round = &s.round
	}
	_, err := pool.Exec(context.Background(), `
		INSERT INTO stream_sessions (session_id, stream_id, started_at, state, round_id, round_closed_at, ended_at)
		VALUES ($1, $2, $3, $4, $5,
		        CASE WHEN $6 THEN pg_catalog.now() END,
		        CASE WHEN $4 = 'ended' THEN pg_catalog.now() END)`,
		s.id, ingest, s.startedAt, s.state, round, s.closed)
	if err != nil {
		t.Fatalf("세션 심기(%s): %v", s.id, err)
	}
}

func seedSegment(t *testing.T, pool *pgxpool.Pool, sessionID string, seq int64, start time.Time, durationMS int32) {
	t.Helper()
	_, err := pool.Exec(context.Background(), `
		INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, session_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		ingest, seq, seq*4000, start, durationMS, fmt.Sprintf("k/%d", seq), sessionID)
	if err != nil {
		t.Fatalf("조각 심기(%s/%d): %v", sessionID, seq, err)
	}
}

func setState(t *testing.T, pool *pgxpool.Pool, eventID, state string, nextAt time.Time) {
	t.Helper()
	_, err := pool.Exec(context.Background(), `
		UPDATE broadcast_lifecycle_outbox
		   SET state = $2, next_at = $3, published_at = CASE WHEN $2 = 'published' THEN pg_catalog.now() END
		 WHERE event_id = $1`, eventID, state, nextAt)
	if err != nil {
		t.Fatalf("상태 바꾸기(%s): %v", eventID, err)
	}
}

// started 는 새 회차 개시 트랜잭션 안에서 넣는 pending 행이다 — 봉투 값(15.3)이 생성 때 굳는다. streamerId 는 발행기가
// resolve 뒤 채우므로 NULL, payload 는 {} 다.
func TestEnqueueStartedInsertsPendingRow(t *testing.T) {
	pool := newTestPool(t)
	seoul := time.FixedZone("KST", 9*60*60)

	enqueueStarted(t, pool, openGate(), "S-A", ingest, base.In(seoul))

	r, ok := readOutbox(t, pool, "S-A.started")
	if !ok {
		t.Fatal("started 행이 없다")
	}
	if r.round != "S-A" || r.eventType != EventStarted || r.sequence != 1 || r.ingest != ingest || r.state != "pending" {
		t.Errorf("started 행 = %+v, want round S-A · %s · 1 · %s · pending", r, EventStarted, ingest)
	}
	if !r.occurredAt.Equal(base) {
		t.Errorf("occurred_at = %v, want %v", r.occurredAt, base)
	}
	if r.streamerID != nil || r.payload != "{}" || r.attempts != 0 {
		t.Errorf("streamer · payload · attempts = %v · %s · %d, want NULL · {} · 0", r.streamerID, r.payload, r.attempts)
	}
}

// 같은 회차를 두 번 넣어도 행은 하나다(ON CONFLICT (event_id) DO NOTHING — 15.5 cc L④). 겹쳐 불리는 완결 평가와 같은
// 이치다.
func TestEnqueueStartedTwiceKeepsOneRow(t *testing.T) {
	pool := newTestPool(t)

	enqueueStarted(t, pool, openGate(), "S-A", ingest, base)
	enqueueStarted(t, pool, openGate(), "S-A", ingest, base.Add(time.Minute))

	if n := countOutbox(t, pool); n != 1 {
		t.Fatalf("outbox 행 = %d, want 1", n)
	}
	if r, _ := readOutbox(t, pool, "S-A.started"); !r.occurredAt.Equal(base) {
		t.Errorf("occurred_at = %v, want 첫 값 %v(덮어쓰지 않음)", r.occurredAt, base)
	}
}

// D45 — 켜는 순간 옛 회차 홍수를 막는다. 게이트 꺼짐 · 허용 목록 밖 · round_id 없는 세션(M6 전)은 INSERT 0 이다.
// 켜진 게이트라도 부르는 역할에 outbox 권한이 없으면 문장 없이 건너뛰고 ERROR lifecycle_enqueue_skipped 를 남긴다.
// 잡는 결함: 허용 목록을 무시하면 드라이런 계정 밖 회차가, NULL 회차를 받으면 옛 세션이 이벤트를 낸다. 권한 판정이
// 없으면 INSERT 가 42501 로 개시 트랜잭션을 롤백한다.
func TestEnqueueStartedSkipsOutsideGate(t *testing.T) {
	discard := slog.New(slog.DiscardHandler)
	skipped := &signals{}
	tests := []struct {
		name   string
		gate   Gate
		round  string
		revoke string // 비지 않으면 media_indexer 역할에서 이 outbox 권한을 거두고 그 역할로 부른다
	}{
		{"게이트_영값_꺼짐", Gate{}, "S-A", ""},
		{"허용_목록_빔", NewGate(nil, discard), "S-A", ""},
		{"허용_목록_밖", NewGate([]string{"zzzzzzzzzzzzzzzzzzzzzzzzzz"}, discard), "S-A", ""},
		{"회차_없음_M6_전", openGate(), "", ""},
		{"권한_없음_INSERT", NewGate([]string{"*"}, slog.New(skipped)), "S-A", "INSERT"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			db := pool
			if tt.revoke != "" {
				role := pgtest.NewMediaIndexerRole(t, pool)
				role.Revoke(t, tt.revoke, "broadcast_lifecycle_outbox")
				db = role.Pool
			}

			enqueueStarted(t, db, tt.gate, tt.round, ingest, base)

			if n := countOutbox(t, pool); n != 0 {
				t.Errorf("outbox 행 = %d, want 0", n)
			}
			if tt.revoke == "" {
				return
			}
			if n := skipped.count(slog.LevelError, "lifecycle_enqueue_skipped",
				"reason", "privilege", "event_type", EventStarted, "round", tt.round); n != 1 {
				t.Errorf("ERROR lifecycle_enqueue_skipped(started · %s) = %d, want 1", tt.round, n)
			}
		})
	}
}

// 허용 목록 안이면 넣는다 — 목록은 stream_id(물리 키)다.
func TestEnqueueStartedAllowsListedStream(t *testing.T) {
	pool := newTestPool(t)

	enqueueStarted(t, pool, NewGate([]string{" other ", ingest}, slog.New(slog.DiscardHandler)), "S-A", ingest, base)

	if n := countOutbox(t, pool); n != 1 {
		t.Errorf("outbox 행 = %d, want 1", n)
	}
}

// D48 — 식별자 규칙을 어기는 회차는 생성 때 abandon 으로 넣는다. pending 으로 두면 SNS 가 영영 거부하거나 소비자가
// 조용히 버리고, 행이 없으면 ended 가 홀로 나갈 수 있다(ended 는 started 가 살아 있을 때만 만든다).
func TestEnqueueStartedAbandonsInvalidRound(t *testing.T) {
	pool := newTestPool(t)
	round := strings.Repeat("a", maxIDLength-len(startedSuffix)+1)

	enqueueStarted(t, pool, openGate(), round, ingest, base)

	r, ok := readOutbox(t, pool, StartedEventID(round))
	if !ok {
		t.Fatal("started 행이 없다")
	}
	if r.state != "abandoned" || r.lastError != "too_long" {
		t.Errorf("상태 · 사유 = %s · %q, want abandoned · too_long", r.state, r.lastError)
	}
}

// D37 — 발행 대상은 커밋된 행뿐이다. 개시 트랜잭션이 롤백되면 outbox 행도 없다(유령 방송 0). 커밋 전에는 다른 연결의
// 발행 조회에도 보이지 않는다.
// 잡는 결함: outbox 를 트랜잭션 밖(별도 연결)에서 쓰면 롤백된 회차의 started 가 남아 발행된다.
func TestEnqueueStartedRollbackLeavesNothingDue(t *testing.T) {
	pool := newTestPool(t)
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatalf("Begin: %v", err)
	}
	if err := EnqueueStarted(ctx, tx, openGate(), "S-A", ingest, base); err != nil {
		t.Fatalf("EnqueueStarted: %v", err)
	}

	due, err := selectDue(ctx, pool, 20)
	if err != nil {
		t.Fatalf("selectDue(커밋 전): %v", err)
	}
	if len(due) != 0 {
		t.Errorf("커밋 전 발행 대상 = %d, want 0", len(due))
	}
	if err := tx.Rollback(ctx); err != nil {
		t.Fatalf("Rollback: %v", err)
	}
	if n := countOutbox(t, pool); n != 0 {
		t.Errorf("롤백 뒤 outbox 행 = %d, want 0", n)
	}
}

// completeRound 는 세션 둘(머리 S-A · 합류 S-B)이 모두 ended 이고 닫힌 회차 S-A 와 그 조각을 심고 started 를 넣는다.
// 마지막 조각 끝 = 10:00:04(S-B seq 9 · 10:00:00 + 4초)다.
func completeRound(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	seedSession(t, pool, session{id: "S-A", round: "S-A", state: "ended", startedAt: base.Add(-time.Hour), closed: true})
	seedSession(t, pool, session{id: "S-B", round: "S-A", state: "ended", startedAt: base.Add(-time.Minute), closed: true})
	seedSegment(t, pool, "S-A", 3, base.Add(-time.Hour), 4000)
	seedSegment(t, pool, "S-A", 4, base.Add(-time.Hour+4*time.Second), 4000)
	seedSegment(t, pool, "S-B", 8, base.Add(-4*time.Second), 4000)
	seedSegment(t, pool, "S-B", 9, base, 4000)
	enqueueStarted(t, pool, openGate(), "S-A", ingest, base.Add(-time.Hour))
}

// D44 — ended 의 occurredAt 은 회차 마지막 조각 끝이다(clip 이 endedAt 으로 저장해 만료 구간이 기댄다). phase 4 가
// 20분 뒤에 와도 값은 그대로다. payload 는 사슬을 가로지른 회차 전체 seq 범위, streamerId 는 생성 때 비운다(D62 —
// 발행 때 started 에서 읽는다), ingestStreamId 는 started 행의 값이다.
// 잡는 결함: occurredAt 을 now() 로 쓰면 clip endedAt 이 최대 20분 늦는다.
func TestEnqueueEndedUsesLastSegmentEnd(t *testing.T) {
	pool := newTestPool(t)
	completeRound(t, pool)
	if _, err := pool.Exec(context.Background(),
		"UPDATE broadcast_lifecycle_outbox SET streamer_id = '42', state = 'published' WHERE event_id = 'S-A.started'"); err != nil {
		t.Fatalf("started 발행 표시: %v", err)
	}

	if !enqueueEnded(t, pool, openGate(), "S-A") {
		t.Fatal("EnqueueEndedIfComplete = false, want true")
	}

	r, ok := readOutbox(t, pool, "S-A.ended")
	if !ok {
		t.Fatal("ended 행이 없다")
	}
	if want := base.Add(4 * time.Second); !r.occurredAt.Equal(want) {
		t.Errorf("occurred_at = %v, want 마지막 조각 끝 %v", r.occurredAt, want)
	}
	if r.eventType != EventEnded || r.sequence != 2 || r.ingest != ingest || r.state != "pending" {
		t.Errorf("ended 행 = %+v, want %s · 2 · %s · pending", r, EventEnded, ingest)
	}
	if r.streamerID != nil {
		t.Errorf("ended streamer_id = %q, want NULL(발행 때 started 에서 읽음)", *r.streamerID)
	}
	var payload struct {
		SegmentRange struct{ FirstSeq, LastSeq int64 } `json:"segmentRange"`
	}
	if err := json.Unmarshal([]byte(r.payload), &payload); err != nil {
		t.Fatalf("payload 해석(%s): %v", r.payload, err)
	}
	if payload.SegmentRange.FirstSeq != 3 || payload.SegmentRange.LastSeq != 9 {
		t.Errorf("segmentRange = %+v, want 3–9", payload.SegmentRange)
	}
}

// ended 는 「회차 닫힘 ∧ 전원 ended」 일 때만 만든다(15.4 · D49 의 「ended 이른 발행」). 겹쳐 불러도 행은 하나다.
// 잡는 결함: 닫힘 조건을 빼면 창(300초 + 60초) 안 재접속을 기다리지 않고 ended 가 나가고, 전원 조건을 빼면 합류한
// 세션이 아직 송출 중인데 ended 가 나간다.
func TestEnqueueEndedRequiresClosedAllEndedRound(t *testing.T) {
	tests := []struct {
		name     string
		sessions []session
		want     bool
	}{
		{"닫힘_전원_ended", []session{
			{id: "S-A", round: "S-A", state: "ended", closed: true},
			{id: "S-B", round: "S-A", state: "ended", closed: true}}, true},
		{"안_닫힘", []session{
			{id: "S-A", round: "S-A", state: "ended"},
			{id: "S-B", round: "S-A", state: "ended"}}, false},
		{"합류_세션_ending", []session{
			{id: "S-A", round: "S-A", state: "ended", closed: true},
			{id: "S-B", round: "S-A", state: "ending", closed: true}}, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			for i, s := range tt.sessions {
				s.startedAt = base.Add(time.Duration(i) * time.Minute)
				seedSession(t, pool, s)
				seedSegment(t, pool, s.id, int64(i), s.startedAt, 4000)
			}
			enqueueStarted(t, pool, openGate(), "S-A", ingest, base)

			got := enqueueEnded(t, pool, openGate(), "S-A")
			again := enqueueEnded(t, pool, openGate(), "S-A")

			_, exists := readOutbox(t, pool, "S-A.ended")
			if got != tt.want || exists != tt.want {
				t.Errorf("enqueued · 행 있음 = %v · %v, want %v", got, exists, tt.want)
			}
			if again {
				t.Errorf("두 번째 EnqueueEndedIfComplete = true, want false(이미 있음)")
			}
		})
	}
}

// ended 는 started 를 낸(낼) 회차만이다 — started 행이 없거나(켜기 전 회차 · 허용 목록 밖) abandon 이면 만들지 않는다
// (15.4 · 15.5 X4 규칙 2). 게이트가 꺼져 있으면 문장 자체를 보내지 않는다(권한이 없을 때 42501 로 phase 4 가 막히지
// 않게 — 7절 활성화 선행 조건). 켜진 게이트라도 부르는 역할에 outbox 권한이 없으면 문장 없이 거짓 · 오류 없음이고
// ERROR lifecycle_enqueue_skipped 를 남긴다.
func TestEnqueueEndedRequiresLiveStartedRow(t *testing.T) {
	skipped := &signals{}
	tests := []struct {
		name    string
		started string // "" 이면 started 행 없음
		gate    Gate
		want    bool
		revoke  string // 비지 않으면 media_indexer 역할에서 이 outbox 권한을 거두고 그 역할로 부른다
	}{
		{"started_pending", "pending", openGate(), true, ""},
		{"started_published", "published", openGate(), true, ""},
		{"started_없음", "", openGate(), false, ""},
		{"started_abandoned", "abandoned", openGate(), false, ""},
		{"게이트_꺼짐", "pending", Gate{}, false, ""},
		{"권한_없음_UPDATE", "pending", NewGate([]string{"*"}, slog.New(skipped)), false, "UPDATE"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			seedSession(t, pool, session{id: "S-A", round: "S-A", state: "ended", startedAt: base, closed: true})
			seedSegment(t, pool, "S-A", 0, base, 4000)
			if tt.started != "" {
				enqueueStarted(t, pool, openGate(), "S-A", ingest, base)
				setState(t, pool, "S-A.started", tt.started, base)
			}
			db := pool
			if tt.revoke != "" {
				role := pgtest.NewMediaIndexerRole(t, pool)
				role.Revoke(t, tt.revoke, "broadcast_lifecycle_outbox")
				db = role.Pool
			}

			if got := enqueueEnded(t, db, tt.gate, "S-A"); got != tt.want {
				t.Errorf("EnqueueEndedIfComplete = %v, want %v", got, tt.want)
			}
			if tt.revoke == "" {
				return
			}
			if n := skipped.count(slog.LevelError, "lifecycle_enqueue_skipped",
				"reason", "privilege", "event_type", EventEnded, "round", "S-A"); n != 1 {
				t.Errorf("ERROR lifecycle_enqueue_skipped(ended · S-A) = %d, want 1", n)
			}
		})
	}
}

// 조각 없는 회차(구조상 드묾)는 occurredAt 을 마지막 세션 끝으로, payload 를 {} 로 둔다 — NULL 로 넣다가 phase 4
// 트랜잭션을 깨지 않는다.
func TestEnqueueEndedWithoutSegmentsFallsBackToEndedAt(t *testing.T) {
	pool := newTestPool(t)
	seedSession(t, pool, session{id: "S-A", round: "S-A", state: "ended", startedAt: base, closed: true})
	enqueueStarted(t, pool, openGate(), "S-A", ingest, base)

	if !enqueueEnded(t, pool, openGate(), "S-A") {
		t.Fatal("EnqueueEndedIfComplete = false, want true")
	}
	if r, _ := readOutbox(t, pool, "S-A.ended"); r.payload != "{}" {
		t.Errorf("payload = %s, want {}", r.payload)
	}
}

// statementCounter 는 풀이 보낸 문장을 모두 센다(pgx.QueryTracer) — pgtest.Role 의 기록과 달리 성공한 불필요 SELECT 도
// 잡는다. 여러 연결이 동시에 부른다.
type statementCounter struct {
	mu     sync.Mutex
	sent   []string
	denied int
}

func (c *statementCounter) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sent = append(c.sent, d.SQL)
	return ctx
}

func (c *statementCounter) TraceQueryEnd(_ context.Context, _ *pgx.Conn, d pgx.TraceQueryEndData) {
	if !pgtest.IsInsufficientPrivilege(d.Err) {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.denied++
}

// containing 은 글자 s 를 담은 문장 수다.
func (c *statementCounter) containing(s string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, sql := range c.sent {
		if strings.Contains(sql, s) {
			n++
		}
	}
	return n
}

// exactly 는 문장 sql 그대로를 보낸 수다.
func (c *statementCounter) exactly(sql string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, s := range c.sent {
		if s == sql {
			n++
		}
	}
	return n
}

// deniedCount 는 42501 로 거부된 문장 수다.
func (c *statementCounter) deniedCount() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.denied
}

// countedRolePool 은 media_indexer 역할에서 outbox 권한 revoke 를 거두고, 그 역할로 붙어 문장마다 계수기를 거치는
// 풀을 돌려준다.
func countedRolePool(t *testing.T, admin *pgxpool.Pool, revoke ...string) (*pgxpool.Pool, *statementCounter) {
	t.Helper()
	role := pgtest.NewMediaIndexerRole(t, admin)
	for _, p := range revoke {
		role.Revoke(t, p, "broadcast_lifecycle_outbox")
	}
	counter := &statementCounter{}
	return pgtest.TracedPool(context.Background(), t, role.Pool, counter), counter
}

// runInTx 는 fn 을 트랜잭션 하나에서 돌리고, 오류가 없으면 커밋한다. 오류는 값으로 돌려준다.
func runInTx(pool *pgxpool.Pool, fn func(context.Context, pgx.Tx) error) error {
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := fn(ctx, tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// J4′(a)(브로드캐스트 층) — 꺼진 게이트 · 목록 밖 개시와 꺼진 게이트의 완결 평가는 outbox 를 글자로 담은 문장을
// 하나도 보내지 않는다(판정 SELECT 포함 — outboxWritableSQL 은 표 이름을 함수 인자 글자로 담는다). outbox 네 권한을
// 모두 거둔 역할로 돌려 오류 · 42501 · skipped 0 도 본다. 같은 계수기 · 같은 역할로 켜진 게이트의 개시를 돌리면 그 수가
// 0 보다 커야 한다(양성 대조 — 계수기가 실제로 본다는 교정이라 결과는 보지 않는다).
// 잡는 결함: 판정을 게이트 판정 앞에 두면(V12) 꺼진 사이드카가 판정 SELECT 를 보내고 skipped 줄을 낸다.
func TestEnqueueOffGateSendsNoOutboxStatement(t *testing.T) {
	tests := []struct {
		name  string
		ended bool // 참이면 완결 평가, 거짓이면 개시
		gate  func(*slog.Logger) Gate
	}{
		{"개시_영값_게이트", false, func(*slog.Logger) Gate { return Gate{} }},
		{"개시_허용_목록_빔", false, func(l *slog.Logger) Gate { return NewGate(nil, l) }},
		{"개시_허용_목록_밖", false, func(l *slog.Logger) Gate {
			return NewGate([]string{"zzzzzzzzzzzzzzzzzzzzzzzzzz"}, l)
		}},
		{"완결_평가_영값_게이트", true, func(*slog.Logger) Gate { return Gate{} }},
		{"완결_평가_허용_목록_빔", true, func(l *slog.Logger) Gate { return NewGate(nil, l) }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			completeRound(t, pool)
			db, counter := countedRolePool(t, pool, outboxPrivileges[:]...)
			sig := &signals{}
			gate := tt.gate(slog.New(sig))

			err := runInTx(db, func(ctx context.Context, tx pgx.Tx) error {
				if tt.ended {
					_, err := EnqueueEndedIfComplete(ctx, tx, gate, "S-A")
					return err
				}
				return EnqueueStarted(ctx, tx, gate, "S-B", ingest, base)
			})

			if err != nil {
				t.Fatalf("꺼진 게이트 적재 = %v, want nil", err)
			}
			if n := counter.containing("broadcast_lifecycle_outbox"); n != 0 {
				t.Errorf("outbox 글자를 담은 문장 = %d, want 0", n)
			}
			if n := counter.deniedCount(); n != 0 {
				t.Errorf("42501 = %d, want 0", n)
			}
			if n := sig.count(slog.LevelError, "lifecycle_enqueue_skipped"); n != 0 {
				t.Errorf("ERROR lifecycle_enqueue_skipped = %d, want 0", n)
			}

			_ = runInTx(db, func(ctx context.Context, tx pgx.Tx) error { // 결과는 보지 않는다 — 계수기 교정만

				return EnqueueStarted(ctx, tx, openGate(), "S-C", ingest, base)
			})
			if n := counter.containing("broadcast_lifecycle_outbox"); n == 0 {
				t.Error("양성 대조: 켜진 게이트 개시 뒤 outbox 글자를 담은 문장 = 0, want > 0(계수기가 보지 못한다)")
			}
		})
	}
}

// J4′(b)(브로드캐스트 층) — 켜진 게이트 × outbox 권한 하나씩 회수(SELECT · INSERT · UPDATE): 개시 · 완결 평가가 오류
// 없이 돌고 42501 0 · 그 INSERT 문장 0 · ERROR lifecycle_enqueue_skipped 1(event_type 일치) · started 행 0 ·
// 완결 평가 (false, nil).
// 잡는 결함: 판정이 없으면 INSERT 회수에서 42501 로 부르는 쪽 트랜잭션이 롤백되고(V2 · V3), SELECT · UPDATE 회수의
// started 는 판정 없이 행이 생겨 started 와 ended 가 함께 서지 않는다. skipped 를 INFO 로 내리면 LOG_LEVEL=error
// 에서 안 보인다(V21).
func TestEnqueueSkipsWithoutOutboxPrivilege(t *testing.T) {
	for _, privilege := range []string{"SELECT", "INSERT", "UPDATE"} {
		t.Run("개시_"+privilege+"_회수", func(t *testing.T) {
			pool := newTestPool(t)
			db, counter := countedRolePool(t, pool, privilege)
			sig := &signals{}
			gate := NewGate([]string{"*"}, slog.New(sig))

			err := runInTx(db, func(ctx context.Context, tx pgx.Tx) error {
				return EnqueueStarted(ctx, tx, gate, "S-B", ingest, base)
			})

			if err != nil {
				t.Fatalf("EnqueueStarted(%s 회수) = %v, want nil", privilege, err)
			}
			if _, ok := readOutbox(t, pool, "S-B.started"); ok {
				t.Errorf("S-B.started 행 있음, want 없음(%s 회수)", privilege)
			}
			checkSkipped(t, sig, counter, EventStarted, "S-B", insertStartedSQL)
		})
		t.Run("완결_평가_"+privilege+"_회수", func(t *testing.T) {
			pool := newTestPool(t)
			completeRound(t, pool)
			db, counter := countedRolePool(t, pool, privilege)
			sig := &signals{}
			gate := NewGate([]string{"*"}, slog.New(sig))

			var enqueued bool
			err := runInTx(db, func(ctx context.Context, tx pgx.Tx) error {
				var err error
				enqueued, err = EnqueueEndedIfComplete(ctx, tx, gate, "S-A")
				return err
			})

			if err != nil || enqueued {
				t.Fatalf("EnqueueEndedIfComplete(%s 회수) = %v · %v, want false · nil", privilege, enqueued, err)
			}
			if _, ok := readOutbox(t, pool, "S-A.ended"); ok {
				t.Errorf("S-A.ended 행 있음, want 없음(%s 회수)", privilege)
			}
			checkSkipped(t, sig, counter, EventEnded, "S-A", insertEndedSQL)
		})
	}
}

// checkSkipped 는 켜진 게이트의 건너뜀 신호를 본다 — ERROR skipped 1 · 그 INSERT 문장 0 · 42501 0.
func checkSkipped(t *testing.T, sig *signals, counter *statementCounter, eventType, round, insertSQL string) {
	t.Helper()
	if n := sig.count(slog.LevelError, "lifecycle_enqueue_skipped",
		"reason", "privilege", "event_type", eventType, "round", round); n != 1 {
		t.Errorf("ERROR lifecycle_enqueue_skipped(%s · %s) = %d, want 1", eventType, round, n)
	}
	if n := counter.exactly(insertSQL); n != 0 {
		t.Errorf("%s INSERT 문장 = %d, want 0", eventType, n)
	}
	if n := counter.deniedCount(); n != 0 {
		t.Errorf("42501 = %d, want 0", n)
	}
}
