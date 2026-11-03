package main

// 발행기 준비가 실패한 의도 켜진 사이드카(강등)의 장부 결과(sgd 설계 8절 — J1′ · J2′ · J3′ · J4′(a) 조립 층 · J9′). 게이트는
// 준비와 무관하게 켜져 있어(kty-Q1 = 안 Ⅰ) 닫는 쪽이 ended 를 닫힘 트랜잭션에서 넣고, 회복한 발행기가 쌓인 편지를 늦게
// 낸다. 강등 판과 준비 성공 판(대조 실행)의 장부 결과는 같아야 한다(F2).
//
// 사이드카 역할은 pgtest.NewMediaIndexerRole 풀이다 — 운영 역할 권한으로 돌아 권한 정합까지 결과로 고정한다.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// degradeToken 은 n 번째 시험 스트림의 토큰이다(발행기가 started 를 resolve 하려면 토큰 꼴이어야 한다).
func degradeToken(n int) string { return fmt.Sprintf("01ARZ3NDEKTSV4RRFFQ69G%04d", n) }

// letters 는 발행 칸이 낸 eventId 를 차례로 모은다.
type letters struct {
	mu  sync.Mutex
	ids []string
}

func (l *letters) publish(_ context.Context, m broadcast.Message) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.ids = append(l.ids, m.DeduplicationID)
	return nil
}

// of 는 회차 round 의 편지를 낸 차례대로 돌려준다.
func (l *letters) of(round string) []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []string
	for _, id := range l.ids {
		if strings.HasPrefix(id, round+".") {
			out = append(out, id)
		}
	}
	return out
}

// stepGate 는 준비 걸음 하나를 처음 failures 번 실패시킨다. 첫 호출(기동 준비)은 곧바로 답하고, 그 뒤 호출(재시도)은
// release 가 닫힐 때까지 기다린다 — 시험이 장부 일을 다 한 뒤에 회복이 일어나게 한다.
type stepGate struct {
	failures int32
	release  chan struct{}
	calls    atomic.Int32
}

func newStepGate(failures int32) *stepGate {
	return &stepGate{failures: failures, release: make(chan struct{})}
}

// fail 은 이번 호출을 실패시킬지다.
func (g *stepGate) fail(ctx context.Context) bool {
	n := g.calls.Add(1)
	if n > 1 {
		select {
		case <-g.release:
		case <-ctx.Done():
			return true
		}
	}
	return n <= g.failures
}

// realSteps 는 pool 위의 실제 권한 표 · 발행기 걸음에 가짜 발행 칸(l)을 끼운 준비 걸음이다.
func realSteps(pool *pgxpool.Pool, l *letters, every time.Duration) publisherSteps {
	return publisherSteps{
		privileges: func(ctx context.Context) ([]string, error) { return broadcast.MissingPrivileges(ctx, pool) },
		snsPublish: func(context.Context) (func(context.Context, broadcast.Message) error, error) {
			return l.publish, nil
		},
		runPublisher: func(ctx context.Context, o broadcast.PublisherOptions) (func(), error) {
			return runLifecyclePublisher(ctx, pool, o)
		},
		every: every,
	}
}

// failingStep 은 steps 의 걸음 step(privilege_check · sns_client · assembly)을 g 로 실패시킨 판이다.
func failingStep(steps publisherSteps, step string, g *stepGate) publisherSteps {
	switch step {
	case "privilege_check":
		next := steps.privileges
		steps.privileges = func(ctx context.Context) ([]string, error) {
			if g.fail(ctx) {
				return nil, errStepFake
			}
			return next(ctx)
		}
	case "sns_client":
		next := steps.snsPublish
		steps.snsPublish = func(ctx context.Context) (func(context.Context, broadcast.Message) error, error) {
			if g.fail(ctx) {
				return nil, errStepFake
			}
			return next(ctx)
		}
	case "assembly":
		next := steps.runPublisher
		steps.runPublisher = func(ctx context.Context, o broadcast.PublisherOptions) (func(), error) {
			if g.fail(ctx) {
				return nil, errStepFake
			}
			return next(ctx, o)
		}
	}
	return steps
}

// dbNow 는 DB 시계다 — 보정 창 · 닫힘 시한을 DB now() 기준 상대값으로 잡는다(고정 시각은 날짜가 지나면 창 밖이다).
func dbNow(t *testing.T, pool *pgxpool.Pool) time.Time {
	t.Helper()
	var now time.Time
	if err := pool.QueryRow(context.Background(), "SELECT pg_catalog.now()").Scan(&now); err != nil {
		t.Fatalf("DB 시계 읽기: %v", err)
	}
	return now
}

// tracedRole 은 media_indexer 역할 풀과, 같은 역할로 붙어 문장을 세는 풀이다.
func tracedRole(t *testing.T, admin *pgxpool.Pool) (*pgtest.Role, *pgxpool.Pool, *outboxCounter) {
	t.Helper()
	role := pgtest.NewMediaIndexerRole(t, admin)
	counter := &outboxCounter{}
	return role, pgtest.TracedPool(context.Background(), t, role.Pool, counter), counter
}

// grantOutbox 는 역할 role 에 outbox 권한 privilege 를 다시 준다(Revoke 의 짝 — 운영의 재부여).
func grantOutbox(t *testing.T, admin *pgxpool.Pool, role *pgtest.Role, privilege string) {
	t.Helper()
	if _, err := admin.Exec(context.Background(), "GRANT "+privilege+" ON broadcast_lifecycle_outbox TO "+role.Name); err != nil {
		t.Fatalf("outbox %s 재부여 실패: %v", privilege, err)
	}
}

// outboxState 는 행 eventID 의 state 다(없으면 "").
func outboxState(t *testing.T, pool *pgxpool.Pool, eventID string) string {
	t.Helper()
	var state string
	err := pool.QueryRow(context.Background(), `SELECT state FROM broadcast_lifecycle_outbox WHERE event_id = $1`, eventID).
		Scan(&state)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("outbox 조회 실패: %v", err)
	}
	return state
}

// j1Segments 는 강등 중 들어오는 조각 수 k 다.
const j1Segments = 3

// closeOutcome 은 닫힘 시나리오의 장부 결과다 — 강등 판과 대조 실행이 같아야 하는 것만 담는다(F2).
type closeOutcome struct {
	segments    int
	state       string
	roundClosed bool
	closedBy    string
}

// closeScenario 는 게이트 gate 로 조립한 결정자 · 정산 조립으로 스트림 stream 에 회차를 열고(조각 k) 닫는다 —
// order "a" = 회차 스캔 CloseRound 뒤 phase 4 MarkChainEnded, "b" = phase 4 뒤 회차 스캔(chain_close.go:197–204 ·
// round_close.go:91–105). 장부 문장은 pool(사이드카 역할)로, 종료 국면 흉내만 admin 으로 보낸다. 회차 id 를 돌려준다.
func closeScenario(t *testing.T, admin, pool *pgxpool.Pool, gate broadcast.Gate, stream, order string) (string, closeOutcome) {
	t.Helper()
	ctx := context.Background()
	discard := slog.New(slog.DiscardHandler)
	d := newSessionDecider(time.Second, 30*time.Second, 300*time.Second, gate, discard)
	start := dbNow(t, admin).Add(-200 * time.Second) // 마지막 조각 끝 ≈ 188초 전 — 300+60초 시한 안
	head := openVia(t, pool, d, stream, 1, start, 4000)
	for seq := int64(2); seq <= j1Segments; seq++ {
		openVia(t, pool, d, stream, seq, start.Add(time.Duration(seq-1)*4*time.Second), 4000)
	}
	reason := map[string]string{"a": "intentional", "b": "offline"}[order]
	if _, err := admin.Exec(ctx, `UPDATE stream_sessions SET state='ending', ending_at=now(), end_reason=$2,
		dvr_state='frozen', vod_state='frozen' WHERE stream_id=$1`, stream, reason); err != nil {
		t.Fatalf("종료 국면 흉내 실패: %v", err)
	}
	var round string
	if err := admin.QueryRow(ctx, `SELECT round_id FROM stream_sessions WHERE session_id=$1`, head).Scan(&round); err != nil {
		t.Fatalf("회차 읽기 실패: %v", err)
	}
	fa := newFinalizeAssembly(config.Config{ReconnectWindow: 300 * time.Second}, gate, discard)
	chain := index.Chain{StreamID: stream, RoundID: round, Head: head, Tail: head, SessionIDs: []string{head}}
	var out closeOutcome
	switch order {
	case "a":
		scanClose(t, pool, round, fa.close, fa.enqueue)
		out.closedBy = closedBy(t, admin, round, "close_round", "")
		markEnded(t, pool, chain, fa)
	case "b":
		markEnded(t, pool, chain, fa)
		out.closedBy = closedBy(t, admin, round, "mark_ended", "")
		scanClose(t, pool, round, index.CloseOptions{Window: 60 * time.Second, Log: discard}, fa.enqueue)
		out.closedBy = closedBy(t, admin, round, "close_round", out.closedBy)
	}
	if err := admin.QueryRow(ctx, `SELECT pg_catalog.count(*) FROM stream_segments WHERE stream_id=$1`, stream).
		Scan(&out.segments); err != nil {
		t.Fatal(err)
	}
	if err := admin.QueryRow(ctx, `SELECT state, round_closed_at IS NOT NULL FROM stream_sessions WHERE session_id=$1`, head).
		Scan(&out.state, &out.roundClosed); err != nil {
		t.Fatal(err)
	}
	return round, out
}

// closedBy 는 회차가 지금 닫혀 있으면 step, 아니면 prev 다(이미 닫힌 회차는 prev 를 지킨다).
func closedBy(t *testing.T, admin *pgxpool.Pool, round, step, prev string) string {
	t.Helper()
	if prev != "" {
		return prev
	}
	var closed bool
	if err := admin.QueryRow(context.Background(), `SELECT bool_or(round_closed_at IS NOT NULL) FROM stream_sessions
		WHERE round_id=$1`, round).Scan(&closed); err != nil {
		t.Fatal(err)
	}
	if closed {
		return step
	}
	return ""
}

// scanClose 는 회차 스캔이 round 를 고르면 CloseRound 로 닫는다.
func scanClose(t *testing.T, pool *pgxpool.Pool, round string, o index.CloseOptions, enqueue index.EndedEnqueuer) {
	t.Helper()
	rounds, err := index.ListClosableRounds(context.Background(), pool, 50, o)
	if err != nil {
		t.Fatalf("회차 스캔 실패: %v", err)
	}
	for _, r := range rounds {
		if r.RoundID != round {
			continue
		}
		if _, err := index.CloseRound(context.Background(), pool, r, o, enqueue); err != nil {
			t.Fatalf("CloseRound 실패: %v", err)
		}
	}
}

// markEnded 는 사슬 phase 4 다.
func markEnded(t *testing.T, pool *pgxpool.Pool, c index.Chain, fa finalizeAssembly) {
	t.Helper()
	if n, err := index.MarkChainEnded(context.Background(), pool, c, fa.close, fa.enqueue); err != nil || n != 1 {
		t.Fatalf("MarkChainEnded = %d err %v, want 1", n, err)
	}
}

// J1′ — 준비가 privilege_check 로 실패한 사이드카 A(실제 권한 있음)가 회차 R 을 두 닫힘 순서로 닫으면, 게이트가 켜져
// 있어 닫힘 커밋 직후 R.ended 가 pending 이다. 발행기 B 가 돌면 started → ended 차례로 published 다. 같은 입력의 준비
// 성공 판(대조 실행)과 장부 결과(조각 k · 세션 state · 닫힘 유무 · 닫은 걸음)가 같다(F2). A 는 42501 0 이다.
// 잡는 결함: 강등이 영값 게이트를 줌 → 닫힘 직후 ended 0 · 기동 줄 없음(J5′).
func TestDegradedSidecarClosesRoundWithEndedPendingJ1(t *testing.T) {
	for i, order := range []string{"a", "b"} {
		t.Run(order, func(t *testing.T) { testDegradedClose(t, order, 10+2*i) })
	}
}

func testDegradedClose(t *testing.T, order string, n int) {
	admin := newWireTestPool(t)
	_, aPool, counter := tracedRole(t, admin)
	log, buf := newLockedLog(slog.LevelInfo)
	readyGate, stopReady := startLifecycleWith(t.Context(), degradeLC, okResolve, (&fakePrep{}).steps(time.Hour),
		slog.New(slog.DiscardHandler))
	defer stopReady()
	down := &fakePrep{privileges: func(context.Context, int32) ([]string, error) { return nil, errStepFake }}
	gate, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, down.steps(time.Hour), log)
	defer stop()

	_, want := closeScenario(t, admin, aPool, readyGate, degradeToken(n), order)
	round, got := closeScenario(t, admin, aPool, gate, degradeToken(n+1), order)

	if got != want || got.segments != j1Segments || !got.roundClosed || got.state != "ended" {
		t.Fatalf("강등 판 %+v · 대조 실행 %+v, want 같음 · 조각 %d · ended · 닫힘", got, want, j1Segments)
	}
	if s := outboxState(t, admin, broadcast.EndedEventID(round)); s != "pending" {
		t.Fatalf("닫힘 커밋 직후 R.ended state %q, want pending — 강등 중에도 게이트는 켜져 있다", s)
	}
	if g := gauges(t, buf); len(g) != 1 || g[0] != (gauge{1, "privilege_check"}) {
		t.Fatalf("lifecycle_degraded %v, want [1 privilege_check] 한 줄", g)
	}
	publishWithB(t, admin, round)
	if _, _, denied := counter.counts(); denied != 0 {
		t.Errorf("A 의 42501 %d, want 0", denied)
	}
}

// publishWithB 는 발행 사이드카 B(운영 역할 풀)를 띄워 회차 round 의 started · ended 가 차례로 published 되는지 본다.
func publishWithB(t *testing.T, admin *pgxpool.Pool, round string) {
	t.Helper()
	b := pgtest.NewMediaIndexerRole(t, admin)
	l := &letters{}
	stop, err := runLifecyclePublisher(t.Context(), b.Pool,
		broadcast.PublisherOptions{Publish: l.publish, Resolve: okResolve, Log: slog.New(slog.DiscardHandler)})
	if err != nil {
		t.Fatalf("발행기 B 기동 실패: %v", err)
	}
	state := waitOutboxState(t, admin, broadcast.EndedEventID(round), "published", 10*time.Second)
	stop()
	got := l.of(round)
	if state != "published" || len(got) != 2 || got[0] != broadcast.StartedEventID(round) || got[1] != broadcast.EndedEventID(round) {
		t.Fatalf("R.ended %q · 편지 %v, want published · started → ended", state, got)
	}
}

// J2′ — J1′ 의 강등 사이드카가 k(3)번째 재시도에서 회복한다(로컬 SNS 대역 · 실제 SNS 클라이언트). 강등 중 쌓인 R 의
// started → ended 가 차례로 나가고, INFO lifecycle_started attempt=3 · ERROR value=0 reason=recovered 각 1 · WARN 2 다.
// 회복 뒤 시도 주기보다 한참 늦게 넣은 행도 published 다(발행기 ctx 는 재시도 고루틴의 긴 ctx — P4).
// 잡는 결함: 재시도 1회(V5) · 발행기를 시도 시한 ctx 에서 땀(V6).
func TestDegradedSidecarRecoversOnKthRetryJ2(t *testing.T) {
	const k = 3
	admin := newWireTestPool(t)
	_, aPool, counter := tracedRole(t, admin)
	assemblyCredentialsEnv(t)
	sns := &snsDouble{}
	srv := httptest.NewServer(sns)
	t.Cleanup(srv.Close)
	t.Setenv("AWS_ENDPOINT_URL_SNS", srv.URL)
	g := newStepGate(k)
	steps := failingStep(realSteps(aPool, nil, 50*time.Millisecond), "privilege_check", g)
	steps.snsPublish = func(ctx context.Context) (func(context.Context, broadcast.Message) error, error) {
		return newSNSPublish(ctx, degradeLC)
	}
	log, buf := newLockedLog(slog.LevelInfo)
	gate, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, steps, log)
	defer stop()

	round, _ := closeScenario(t, admin, aPool, gate, degradeToken(20), "a")
	close(g.release)
	if s := waitOutboxState(t, admin, broadcast.EndedEventID(round), "published", 10*time.Second); s != "published" {
		t.Fatalf("R.ended %q, want published — 전체 %s", s, buf)
	}
	time.Sleep(4 * steps.every) // 회복 뒤 시도 주기보다 늦게 넣는다
	late := lateStarted(t, admin, degradeToken(21))
	if s := waitOutboxState(t, admin, broadcast.StartedEventID(late), "published", 10*time.Second); s != "published" {
		t.Fatalf("회복 뒤 늦게 넣은 started %q, want published — 발행기가 조용히 멈췄다", s)
	}
	stop()
	requireRecoveryLines(t, buf, k)
	if got := snsEventIDs(sns.got()); len(got) != 3 || got[0] != broadcast.StartedEventID(round) ||
		got[1] != broadcast.EndedEventID(round) || got[2] != broadcast.StartedEventID(late) {
		t.Fatalf("SNS 편지 %v, want R.started → R.ended → 늦은 started", got)
	}
	if _, _, denied := counter.counts(); denied != 0 {
		t.Errorf("A 의 42501 %d, want 0", denied)
	}
}

// requireRecoveryLines 는 k번째 재시도 회복의 줄(INFO started attempt=k · ERROR recovered · WARN k−1)을 본다.
func requireRecoveryLines(t *testing.T, buf *lockedLog, k int) {
	t.Helper()
	started := buf.lines(t, "lifecycle_started")
	if len(started) != 1 || started[0]["attempt"] != float64(k) || started[0]["level"] != "INFO" {
		t.Errorf("lifecycle_started %v, want INFO attempt=%d 한 줄", started, k)
	}
	if g := gauges(t, buf); len(g) != 2 || g[1] != (gauge{0, "recovered"}) {
		t.Errorf("lifecycle_degraded %v, want [1 privilege_check] → [0 recovered]", g)
	}
	if w := buf.lines(t, "lifecycle_prepare_retry_failed"); len(w) != k-1 {
		t.Errorf("WARN lifecycle_prepare_retry_failed %d 줄, want %d", len(w), k-1)
	}
}

// lateStarted 는 회차 하나의 started 를 pending 으로 넣고 회차 id 를 돌려준다.
func lateStarted(t *testing.T, admin *pgxpool.Pool, stream string) string {
	t.Helper()
	round := "LATE-" + stream
	if _, err := admin.Exec(context.Background(), `
INSERT INTO broadcast_lifecycle_outbox (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id)
VALUES ($1, $2, $3, 1, pg_catalog.now(), $4)`, broadcast.StartedEventID(round), round, broadcast.EventStarted, stream); err != nil {
		t.Fatalf("늦은 started 픽스처 실패: %v", err)
	}
	return round
}

// snsEventIDs 는 SNS 대역이 받은 편지 본문의 eventId 들이다.
func snsEventIDs(msgs []string) []string {
	var out []string
	for _, m := range msgs {
		if _, rest, ok := strings.Cut(m, `"eventId":"`); ok {
			id, _, _ := strings.Cut(rest, `"`)
			out = append(out, id)
		}
	}
	return out
}

// seedClosable 는 닫는 쪽이 phase 4 로 닫을 회차 하나다(DB now() 기준 · 보정 창 안) — started 는 이미 낸 행(사용자 42),
// 세션은 의도적 종료 · 동결 끝, 조각 하나. 사슬 값을 돌려준다.
func seedClosable(t *testing.T, admin *pgxpool.Pool, stream, round string) index.Chain {
	t.Helper()
	start := dbNow(t, admin).Add(-time.Hour)
	seedOutboxSession(t, admin, round, stream, "ending", "intentional", round, "", "frozen", start, time.Time{})
	seedOutboxSegment(t, admin, stream, round, 1, start)
	if _, err := admin.Exec(context.Background(), `
INSERT INTO broadcast_lifecycle_outbox (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, streamer_id, state)
VALUES ($1, $2, $3, 1, $4, $5, '42', 'published')`, broadcast.StartedEventID(round), round, broadcast.EventStarted, start, stream); err != nil {
		t.Fatalf("started 픽스처 실패: %v", err)
	}
	return index.Chain{StreamID: stream, RoundID: round, Head: round, Tail: round, SessionIDs: []string{round}}
}

// endedRows 는 회차 round 의 ended 행 수다.
func endedRows(t *testing.T, admin *pgxpool.Pool, round string) int {
	t.Helper()
	return outboxCount(t, admin, round, broadcast.EventEnded)
}

// J3′ — 준비 실패 사유마다 강등 사이드카 A 가 회차 R 을 닫고 회복하면 R.ended 가 정확히 한 행 · published 다.
// privilege_check · sns_client · assembly 는 닫는 쪽이 넣은 행이 나가고(보정 0), 기동 privilege(outbox INSERT 없음)는
// 닫는 쪽이 건너뛴 뒤(ERROR skipped) 재부여 → 재시도 회복 → 회복 발행기의 첫 tick 보정이 넣는다(P0′).
// 잡는 결함: 발행기 보정 제거(V1 — 기동 privilege 행) · 강등이 영값 게이트(J5′).
func TestDegradedReasonsEndWithOneEndedJ3(t *testing.T) {
	for i, reason := range []string{"privilege_check", "sns_client", "assembly", "privilege"} {
		t.Run(reason, func(t *testing.T) { testDegradedReason(t, reason, 30+i) })
	}
}

func testDegradedReason(t *testing.T, reason string, n int) {
	admin := newWireTestPool(t)
	role, aPool, counter := tracedRole(t, admin)
	l := &letters{}
	steps := realSteps(aPool, l, 50*time.Millisecond)
	g := newStepGate(1)
	release := func() { close(g.release) }
	if reason == "privilege" {
		role.Revoke(t, "INSERT", "broadcast_lifecycle_outbox")
		release = func() { grantOutbox(t, admin, role, "INSERT") }
	} else {
		steps = failingStep(steps, reason, g)
	}
	log, buf := newLockedLog(slog.LevelInfo)
	gate, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, steps, log)
	defer stop()
	stream, round := degradeToken(n), fmt.Sprintf("J3-%s", reason)
	chain := seedClosable(t, admin, stream, round)

	markEnded(t, aPool, chain, newFinalizeAssembly(config.Config{ReconnectWindow: 300 * time.Second}, gate, log))
	wantPending := map[bool]int{true: 0, false: 1}[reason == "privilege"]
	if got := endedRows(t, admin, round); got != wantPending {
		t.Fatalf("닫힘 직후 R.ended %d 행, want %d", got, wantPending)
	}
	release()
	state := waitOutboxState(t, admin, broadcast.EndedEventID(round), "published", 15*time.Second)
	stop()

	if got := endedRows(t, admin, round); state != "published" || got != 1 || len(l.of(round)) != 1 {
		t.Fatalf("R.ended %q · %d 행 · 편지 %v, want published · 1 · 하나 — 전체 %s", state, got, l.of(round), buf)
	}
	if gs := gauges(t, buf); len(gs) != 2 || gs[0] != (gauge{1, reason}) || gs[1] != (gauge{0, "recovered"}) {
		t.Errorf("lifecycle_degraded %v, want [1 %s] → [0 recovered]", gs, reason)
	}
	// 건너뜀 줄은 완결 평가 호출마다 찍혀 여러 줄일 수 있다 — 있음 / 없음으로만 본다.
	repaired, skipped := buf.lines(t, "lifecycle_ended_repaired"), buf.lines(t, "lifecycle_enqueue_skipped")
	wantRepaired := map[bool]int{true: 1, false: 0}[reason == "privilege"]
	if len(repaired) != wantRepaired || (len(skipped) > 0) != (reason == "privilege") {
		t.Errorf("repaired %v · skipped %v, want 보정 %d 줄 · 건너뜀 있음 %v", repaired, skipped, wantRepaired, reason == "privilege")
	}
	if _, _, denied := counter.counts(); denied != 0 {
		t.Errorf("A 의 42501 %d, want 0", denied)
	}
}

// J9′ — 강등(privilege_check · 실제 권한 있음) 중 새로 연 회차의 started 는 pending 으로 쌓이고 게이트 Enabled() 는
// 참 그대로이며, 회복한 발행기가 낸다. 기동 privilege 강등(outbox INSERT 없음)이면 그 회차 started 는 0 이고 ERROR
// skipped(started) 1 · 42501 0 이다(안 Ⅰ — started 는 영구 생략 · P31′).
// 잡는 결함: 강등이 영값 게이트를 줌 → 강등 중 started 0 · skipped 0(J5′).
func TestDegradedGateEnqueuesStartedJ9(t *testing.T) {
	t.Run("실제_권한_있음", testDegradedStartedPublishedAfterRecovery)
	t.Run("기동_권한_없음", testStartupPrivilegeSkipsStarted)
}

func testDegradedStartedPublishedAfterRecovery(t *testing.T) {
	admin := newWireTestPool(t)
	_, aPool, _ := tracedRole(t, admin)
	l := &letters{}
	g := newStepGate(1)
	steps := failingStep(realSteps(aPool, l, 50*time.Millisecond), "privilege_check", g)
	log, buf := newLockedLog(slog.LevelInfo)
	gate, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, steps, log)
	defer stop()

	d := newSessionDecider(time.Second, 30*time.Second, 300*time.Second, gate, log)
	round := openVia(t, aPool, d, degradeToken(40), 1, dbNow(t, admin), 4000)
	if s := outboxState(t, admin, broadcast.StartedEventID(round)); !gate.Enabled() || s != "pending" {
		t.Fatalf("강등 중 게이트 켬 %v · started %q, want 참 · pending", gate.Enabled(), s)
	}
	close(g.release)
	if s := waitOutboxState(t, admin, broadcast.StartedEventID(round), "published", 10*time.Second); s != "published" {
		t.Fatalf("회복 뒤 started %q, want published — 전체 %s", s, buf)
	}
}

func testStartupPrivilegeSkipsStarted(t *testing.T) {
	admin := newWireTestPool(t)
	role, aPool, counter := tracedRole(t, admin)
	role.Revoke(t, "INSERT", "broadcast_lifecycle_outbox")
	log, buf := newLockedLog(slog.LevelInfo)
	gate, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, realSteps(aPool, &letters{}, time.Hour), log)
	defer stop()

	stream := degradeToken(41)
	d := newSessionDecider(time.Second, 30*time.Second, 300*time.Second, gate, log)
	if id := openVia(t, aPool, d, stream, 1, dbNow(t, admin), 4000); id == "" || !gate.Enabled() {
		t.Fatalf("개시 %q · 게이트 켬 %v, want 개시 · 참", id, gate.Enabled())
	}
	skipped := buf.lines(t, "lifecycle_enqueue_skipped")
	if n := startedTotal(t, admin, stream); n != 0 || len(skipped) != 1 || skipped[0]["event_type"] != broadcast.EventStarted ||
		skipped[0]["level"] != "ERROR" {
		t.Fatalf("started %d 행 · skipped %v, want 0 · ERROR started 한 줄", n, skipped)
	}
	if g := gauges(t, buf); len(g) != 1 || g[0] != (gauge{1, "privilege"}) {
		t.Errorf("lifecycle_degraded %v, want [1 privilege]", g)
	}
	if _, _, denied := counter.counts(); denied != 0 {
		t.Errorf("42501 %d, want 0", denied)
	}
}

// J4′(a) 조립 층 — 설정으로 꺼진 기동(no_topic · no_streams · no_resolver)은 준비 걸음이 쓰는 풀에 문장을 하나도 보내지
// 않고(재시도 · 기동 줄 0), 그 영값 게이트로 조립한 outbox 권한 전무 역할의 개시 · 닫힘 · phase 4 가 outbox 글자 문장
// 0 · 42501 0 으로 성공한다. 같은 계수기 · 같은 역할로 켜진 게이트를 돌리면 outbox 글자 문장이 생긴다(양성 대조 —
// 계수기가 실제로 본다). 잡는 결함: 꺼진 갈래가 준비 · 재시도를 돎(V10) · 판정을 allows 앞으로(V12).
func TestOffSidecarSendsNoOutboxStatementsJ4(t *testing.T) {
	admin := newWireTestPool(t)
	for i, c := range []struct {
		name string
		cfg  config.Config
	}{
		{"no_topic", lifecycleCfg("", "*")},
		{"no_streams", lifecycleCfg(lifecycleTopic)},
		{"no_resolver", lifecycleCfg(lifecycleTopic, "*")},
	} {
		role := pgtest.NewMediaIndexerRole(t, admin)
		for _, p := range []string{"SELECT", "INSERT", "UPDATE", "DELETE"} {
			role.Revoke(t, p, "broadcast_lifecycle_outbox")
		}
		prep := &outboxCounter{}
		log, buf := expiryLog()
		gate, stop := startLifecycle(t.Context(), c.cfg, pgtest.TracedPool(t.Context(), t, role.Pool, prep), nil, log)
		stop()
		if total, _, _ := prep.counts(); gate.Enabled() || total != 0 {
			t.Errorf("%s: 게이트 켬 %v · 준비 풀 문장 %d, want 거짓 · 0", c.name, gate.Enabled(), total)
		}
		requireNoPrepareLines(t, buf)
		if total, outbox, denied := ledgerWith(t, admin, role.Pool, gate, 50+2*i); total == 0 || outbox != 0 || denied != 0 {
			t.Errorf("%s: 장부 문장 %d · outbox 글자 %d · 42501 %d, want >0 · 0 · 0", c.name, total, outbox, denied)
		}
		if _, outbox, denied := ledgerWith(t, admin, role.Pool, allStreams(), 51+2*i); outbox == 0 || denied != 0 {
			t.Errorf("%s 양성 대조: 켜진 게이트의 outbox 글자 %d · 42501 %d, want >0 · 0", c.name, outbox, denied)
		}
	}
}

// ledgerWith 는 게이트 gate 로 조립한 결정자 · 정산 조립으로 역할 풀 위에서 개시 하나와 회차 하나의 닫힘 · phase 4 를
// 돌리고 그 문장 계수(전체 · outbox 글자 · 42501)를 돌려준다.
func ledgerWith(t *testing.T, admin, rolePool *pgxpool.Pool, gate broadcast.Gate, n int) (total, outbox, denied int) {
	t.Helper()
	counter := &outboxCounter{}
	pool := pgtest.TracedPool(context.Background(), t, rolePool, counter)
	discard := slog.New(slog.DiscardHandler)
	d := newSessionDecider(time.Second, 30*time.Second, 300*time.Second, gate, discard)
	if id := openVia(t, pool, d, degradeToken(n), 1, dbNow(t, admin), 4000); id == "" {
		t.Fatal("개시가 서지 않았다")
	}
	chain := seedClosable(t, admin, degradeToken(n)+"-c", fmt.Sprintf("J4-R%d", n))
	markEnded(t, pool, chain, newFinalizeAssembly(config.Config{ReconnectWindow: 300 * time.Second}, gate, discard))
	return counter.counts()
}

// J3′ privilege 실행 중 — 발행 중인 사이드카 B 의 outbox INSERT 를 거두면 B 가 닫은 회차 R 은 닫는 쪽에서 건너뛰고
// (ERROR skipped), 다음 30초 보정 주기의 INSERT 가 42501 로 WARN lifecycle_store_failed stage=repair 를 남긴다. 재부여
// 뒤 다음 보정 주기가 R.ended 한 행을 넣어 낸다. 보정 주기는 운영 값(30초) 그대로다 — 회당 약 65초.
// 잡는 결함: 발행기 보정 제거(V1) · 실행 중 회수 뒤 영구 누락(P0′).
func TestRuntimeRevokeEndedRepairedAfterRegrantJ3(t *testing.T) {
	admin := newWireTestPool(t)
	role, bPool, _ := tracedRole(t, admin)
	warm := lateStarted(t, admin, degradeToken(60))
	if _, err := admin.Exec(context.Background(), `UPDATE broadcast_lifecycle_outbox SET streamer_id='42' WHERE round_id=$1`,
		warm); err != nil {
		t.Fatal(err)
	}
	l := &letters{}
	log, buf := newLockedLog(slog.LevelInfo)
	gate, stop := startLifecycleWith(t.Context(), degradeLC, okResolve, realSteps(bPool, l, time.Hour), log)
	defer stop()
	if s := waitOutboxState(t, admin, broadcast.StartedEventID(warm), "published", 10*time.Second); s != "published" {
		t.Fatalf("첫 tick 확인용 started %q, want published — 전체 %s", s, buf)
	}

	role.Revoke(t, "INSERT", "broadcast_lifecycle_outbox")
	round := "J3-RUNTIME"
	markEnded(t, bPool, seedClosable(t, admin, degradeToken(61), round),
		newFinalizeAssembly(config.Config{ReconnectWindow: 300 * time.Second}, gate, log))
	if n := endedRows(t, admin, round); n != 0 || len(buf.lines(t, "lifecycle_enqueue_skipped")) == 0 {
		t.Fatalf("회수 뒤 닫힘의 R.ended %d 행 · skipped %v, want 0 · 있음", n, buf.lines(t, "lifecycle_enqueue_skipped"))
	}
	if !waitUntil(45*time.Second, func() bool { return repairFailed(t, buf) }) {
		t.Fatalf("보정 주기의 42501 이 store_failed stage=repair 로 남지 않았다 — 전체 %s", buf)
	}
	grantOutbox(t, admin, role, "INSERT")
	state := waitOutboxState(t, admin, broadcast.EndedEventID(round), "published", 45*time.Second)
	stop()

	if n := endedRows(t, admin, round); state != "published" || n != 1 || len(l.of(round)) != 1 {
		t.Fatalf("재부여 뒤 R.ended %q · %d 행 · 편지 %v, want published · 1 · 하나", state, n, l.of(round))
	}
	if r := buf.lines(t, "lifecycle_ended_repaired"); len(r) != 1 || r[0]["round"] != round {
		t.Errorf("lifecycle_ended_repaired %v, want round=%s 한 줄", r, round)
	}
}

// repairFailed 는 보정 문장 실패 줄이 남았는지다.
func repairFailed(t *testing.T, buf *lockedLog) bool {
	for _, l := range buf.lines(t, "lifecycle_store_failed") {
		if l["stage"] == "repair" {
			return true
		}
	}
	return false
}
