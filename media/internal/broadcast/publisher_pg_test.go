package broadcast

// 발행기 실 PG 시험(POK-196 M6-8 커밋 ③ · 설계 M6 15.5 · 15.8 · D35 · D39–D41 · D46 · D47 · D48 · D62 · D63 · K10).
// 바깥 호출(resolve · SNS)은 함수 칸 가짜다 — LocalStack 은 쓰지 않는다(15.7).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
)

// signals 는 신호 발화를 잡는 슬로그 핸들러다(발행 고루틴과 시험 고루틴이 함께 쓴다).
type signals struct {
	mu      sync.Mutex
	records []slog.Record
}

func (s *signals) Enabled(context.Context, slog.Level) bool { return true }
func (s *signals) Handle(_ context.Context, r slog.Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.records = append(s.records, r)
	return nil
}
func (s *signals) WithAttrs([]slog.Attr) slog.Handler { return s }
func (s *signals) WithGroup(string) slog.Handler      { return s }

// count 는 그 이름 · 등급의 신호 수다. attrs 가 있으면 그 키=값을 모두 단 신호만 센다.
func (s *signals) count(level slog.Level, name string, attrs ...string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, r := range s.records {
		if r.Level != level || r.Message != name {
			continue
		}
		got := map[string]string{}
		r.Attrs(func(a slog.Attr) bool { got[a.Key] = a.Value.String(); return true })
		ok := true
		for i := 0; i+1 < len(attrs); i += 2 {
			ok = ok && got[attrs[i]] == attrs[i+1]
		}
		if ok {
			n++
		}
	}
	return n
}

// fakeSNS 는 발행 함수 칸 가짜다. fail 이 있으면 n 번째(0부터) 호출의 결과를 정한다.
type fakeSNS struct {
	mu   sync.Mutex
	msgs []Message
	fail func(n int) error
}

func (f *fakeSNS) publish(_ context.Context, m Message) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := len(f.msgs)
	f.msgs = append(f.msgs, m)
	if f.fail != nil {
		return f.fail(n)
	}
	return nil
}

func (f *fakeSNS) sent() []Message {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]Message(nil), f.msgs...)
}

// fakeResolve 는 resolve 함수 칸 가짜다. 답은 answer 가 정하고, 묻은 토큰을 남긴다.
type fakeResolve struct {
	mu     sync.Mutex
	asked  []string
	answer func(n int) (keyresolve.Verdict, error)
}

func (f *fakeResolve) resolve(_ context.Context, token string) (keyresolve.Verdict, error) {
	f.mu.Lock()
	n := len(f.asked)
	f.asked = append(f.asked, token)
	answer := f.answer
	f.mu.Unlock()
	return answer(n)
}

func (f *fakeResolve) calls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.asked)
}

func validUser(int) (keyresolve.Verdict, error) {
	return keyresolve.Verdict{Valid: true, UserID: 42}, nil
}

func newPublisher(t *testing.T, pool *pgxpool.Pool, sns *fakeSNS, res *fakeResolve, sig *signals) *Publisher {
	t.Helper()
	o := PublisherOptions{Publish: sns.publish, Log: slog.New(sig), Interval: 10 * time.Millisecond}
	if res != nil {
		o.Resolve = res.resolve
	}
	p, err := NewPublisher(pool, o)
	if err != nil {
		t.Fatalf("NewPublisher: %v", err)
	}
	return p
}

// makeDue 는 미뤄 둔 pending 행을 지금 고를 수 있게 당긴다(백오프 기다림 대신).
func makeDue(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	if _, err := pool.Exec(context.Background(),
		"UPDATE broadcast_lifecycle_outbox SET next_at = pg_catalog.now() - interval '1 second' WHERE state = 'pending'"); err != nil {
		t.Fatalf("next_at 당기기: %v", err)
	}
}

func decode(t *testing.T, m Message) Envelope {
	t.Helper()
	var e Envelope
	if err := json.Unmarshal(m.Body, &e); err != nil {
		t.Fatalf("봉투 해석(%s): %v", m.Body, err)
	}
	return e
}

// started 는 resolve 로 얻은 userId 를 streamerId 로 싣고, MessageGroupId = 회차 · MessageDeduplicationId = eventId 로
// 낸다(계약9 2절). userId 는 행에 남아 ended 가 다시 묻지 않고 같은 값을 싣는다(15.3 — 재발급 뒤 REVOKED 회피).
func TestPublisherPublishesRoundWithResolvedStreamer(t *testing.T) {
	pool := newTestPool(t)
	sns, res, sig := &fakeSNS{}, &fakeResolve{answer: validUser}, &signals{}
	p := newPublisher(t, pool, sns, res, sig)
	insertRow(t, pool, "S-A", 1, "pending", base, nil)

	p.tick(context.Background())
	insertRow(t, pool, "S-A", 2, "pending", base, nil)
	p.tick(context.Background())

	msgs := sns.sent()
	if len(msgs) != 2 {
		t.Fatalf("발행 = %d 통, want 2", len(msgs))
	}
	for i, want := range []struct {
		id   string
		typ  string
		seq  int64
		body string
	}{{"S-A.started", EventStarted, 1, ""}, {"S-A.ended", EventEnded, 2, ""}} {
		m, e := msgs[i], decode(t, msgs[i])
		if m.GroupID != "S-A" || m.DeduplicationID != want.id || e.EventID != want.id || e.EventType != want.typ ||
			e.Sequence != want.seq || e.StreamerID != "42" || e.StreamID != "S-A" || e.IngestStreamID != ingest {
			t.Errorf("%d번째 = %+v · %+v, want %s · %s · seq %d · streamer 42", i, m, e, want.id, want.typ, want.seq)
		}
	}
	if res.calls() != 1 || res.asked[0] != ingest {
		t.Errorf("resolve 호출 = %v, want [%s] 한 번", res.asked, ingest)
	}
	for _, id := range []string{"S-A.started", "S-A.ended"} {
		if r, _ := readOutbox(t, pool, id); r.state != "published" || !r.published {
			t.Errorf("%s 상태 = %s, want published", id, r.state)
		}
	}
	if n := sig.count(slog.LevelInfo, "lifecycle_published", "event_id", "S-A.ended"); n != 1 {
		t.Errorf("lifecycle_published(S-A.ended) = %d, want 1", n)
	}
}

// D35 — 결과 모름(시한 · 연결 끊김) 뒤 재시도는 같은 eventId · 같은 본문이다. FIFO 중복 제거(5분)와 소비자 eventId
// 멱등이 그 위에 선다.
// 잡는 결함: 시도마다 새 id 를 쓰면 SNS 가 둘 다 받아 같은 편지가 두 번 간다.
func TestPublisherRetriesUnknownOutcomeWithSameID(t *testing.T) {
	pool := newTestPool(t)
	sns := &fakeSNS{fail: func(n int) error {
		if n == 0 {
			return context.DeadlineExceeded
		}
		return nil
	}}
	p := newPublisher(t, pool, sns, &fakeResolve{answer: validUser}, &signals{})
	insertRow(t, pool, "S-A", 1, "pending", base, nil)

	p.tick(context.Background())
	if r, _ := readOutbox(t, pool, "S-A.started"); r.state != "pending" || r.attempts != 1 {
		t.Fatalf("첫 시도 뒤 = %s · attempts %d, want pending · 1", r.state, r.attempts)
	}
	makeDue(t, pool)
	p.tick(context.Background())

	msgs := sns.sent()
	if len(msgs) != 2 {
		t.Fatalf("발행 시도 = %d, want 2", len(msgs))
	}
	if msgs[0].DeduplicationID != msgs[1].DeduplicationID || string(msgs[0].Body) != string(msgs[1].Body) {
		t.Errorf("재시도가 다른 편지다:\n%+v\n%+v", msgs[0], msgs[1])
	}
	if r, _ := readOutbox(t, pool, "S-A.started"); r.state != "published" || r.attempts != 2 {
		t.Errorf("재시도 뒤 = %s · attempts %d, want published · 2", r.state, r.attempts)
	}
}

// D39 — SNS 장애는 백오프 재시도다(1–2–4초 … 상한 60초 · 무한). 같은 행의 연속 실패 WARN 은 한 줄만, 가장 오래된
// pending 이 60초를 넘으면 lifecycle_backlog(ERROR 게이지)를 켜고 비면 끈다.
func TestPublisherBacksOffDuringSNSOutage(t *testing.T) {
	pool := newTestPool(t)
	down := true
	sns := &fakeSNS{fail: func(int) error {
		if down {
			return errors.New("sns 장애")
		}
		return nil
	}}
	sig := &signals{}
	p := newPublisher(t, pool, sns, &fakeResolve{answer: validUser}, sig)
	insertRow(t, pool, "S-A", 1, "pending", base, nil)
	if _, err := pool.Exec(context.Background(),
		"UPDATE broadcast_lifecycle_outbox SET created_at = pg_catalog.now() - interval '2 minutes'"); err != nil {
		t.Fatalf("created_at 되돌리기: %v", err)
	}

	for range 3 {
		p.tick(context.Background())
		makeDue(t, pool)
	}

	r, _ := readOutbox(t, pool, "S-A.started")
	if r.state != "pending" || r.attempts != 3 || r.lastError != "sns: unknown" {
		t.Errorf("장애 중 = %s · attempts %d · %q, want pending · 3 · sns: unknown", r.state, r.attempts, r.lastError)
	}
	if n := sig.count(slog.LevelWarn, "lifecycle_publish_retry"); n != 1 {
		t.Errorf("lifecycle_publish_retry = %d 줄, want 1(같은 행 연속은 한 줄)", n)
	}
	if n := sig.count(slog.LevelError, "lifecycle_backlog", "value", "1"); n != 1 {
		t.Errorf("lifecycle_backlog value=1 = %d, want 1(켜질 때 한 번)", n)
	}
	down = false
	p.tick(context.Background())
	p.tick(context.Background())
	if n := sig.count(slog.LevelError, "lifecycle_backlog", "value", "0"); n != 1 {
		t.Errorf("lifecycle_backlog value=0 = %d, want 1(비면 끔)", n)
	}
}

// 백오프 표 — 시도 횟수 0 부터 1초 · 2초 · 4초 … 상한 60초.
func TestRetryDelay(t *testing.T) {
	for attempts, want := range map[int]time.Duration{
		0: time.Second, 1: 2 * time.Second, 2: 4 * time.Second, 5: 32 * time.Second, 6: 60 * time.Second,
		100: 60 * time.Second,
	} {
		if got := retryDelay(attempts); got != want {
			t.Errorf("retryDelay(%d) = %v, want %v", attempts, got, want)
		}
	}
}

// D40 — resolve 가 valid:false(REVOKED · NOT_FOUND · MALFORMED)면 그 회차는 내지 않는다 — started 를 버리고 같은
// 회차 ended 도 버리며 ERROR 를 남긴다(K10 — 틀린 값의 오권한보다 누락 · 추측 · 캐시 금지).
// 잡는 결함: valid:false 를 재시도로 다루면 재발급된 키의 회차가 영원히 막히고 resolve 를 두드린다.
func TestPublisherAbandonsInvalidStreamer(t *testing.T) {
	for _, reason := range []string{"REVOKED", "NOT_FOUND", "MALFORMED"} {
		t.Run(reason, func(t *testing.T) {
			pool := newTestPool(t)
			sns, sig := &fakeSNS{}, &signals{}
			res := &fakeResolve{answer: func(int) (keyresolve.Verdict, error) {
				return keyresolve.Verdict{Reason: reason}, nil
			}}
			p := newPublisher(t, pool, sns, res, sig)
			insertRow(t, pool, "S-A", 1, "pending", base, nil)
			insertRow(t, pool, "S-A", 2, "pending", base, nil)

			p.tick(context.Background())

			if len(sns.sent()) != 0 {
				t.Errorf("발행 = %d, want 0", len(sns.sent()))
			}
			for id, want := range map[string]string{"S-A.started": "streamer_invalid", "S-A.ended": "started_abandoned"} {
				if r, _ := readOutbox(t, pool, id); r.state != "abandoned" || r.lastError != want {
					t.Errorf("%s = %s · %q, want abandoned · %q", id, r.state, r.lastError, want)
				}
			}
			if n := sig.count(slog.LevelError, "lifecycle_abandoned", "event_id", "S-A.started", "reason", "streamer_invalid"); n != 1 {
				t.Errorf("lifecycle_abandoned reason=streamer_invalid = %d, want 1", n)
			}
		})
	}
}

// D41 — auth 장애(판단 불가)는 백오프 재시도다. 행은 pending 이고 WARN lifecycle_resolve_failed 에 상태 코드를 단다.
// 응답 본문 · 토큰은 어디에도 싣지 않는다.
func TestPublisherRetriesWhenResolveUndecided(t *testing.T) {
	pool := newTestPool(t)
	sns, sig := &fakeSNS{}, &signals{}
	res := &fakeResolve{answer: func(int) (keyresolve.Verdict, error) {
		return keyresolve.Verdict{}, &keyresolve.UndecidedError{Kind: "status", Status: 503}
	}}
	p := newPublisher(t, pool, sns, res, sig)
	insertRow(t, pool, "S-A", 1, "pending", base, nil)

	p.tick(context.Background())

	r, _ := readOutbox(t, pool, "S-A.started")
	if r.state != "pending" || r.attempts != 1 || r.lastError != "resolve: status 503" || r.streamerID != nil {
		t.Errorf("auth 장애 뒤 = %+v, want pending · attempts 1 · resolve: status 503 · streamer NULL", r)
	}
	if len(sns.sent()) != 0 {
		t.Errorf("발행 = %d, want 0", len(sns.sent()))
	}
	if n := sig.count(slog.LevelWarn, "lifecycle_resolve_failed", "status", "503"); n != 1 {
		t.Errorf("lifecycle_resolve_failed status=503 = %d, want 1", n)
	}
}

// D62 — auth 장애 중 회차가 완결돼도 ended 는 streamerId 없이 나가지 않는다. 복구 뒤 started → ended 순서로, 둘 다
// 같은 streamerId 로 나간다(ended 는 발행 때 published started 에서 읽는다).
// 잡는 결함: ended 가 자기 행의 streamer_id(생성 때 값 — 장애 중이라 NULL)를 쓰면 ended 가 버려지거나 빈 값으로 나간다.
func TestPublisherCarriesStreamerToEndedAfterAuthOutage(t *testing.T) {
	pool := newTestPool(t)
	sns := &fakeSNS{}
	res := &fakeResolve{answer: func(n int) (keyresolve.Verdict, error) {
		if n == 0 {
			return keyresolve.Verdict{}, &keyresolve.UndecidedError{Kind: "status", Status: 503}
		}
		return keyresolve.Verdict{Valid: true, UserID: 42}, nil
	}}
	p := newPublisher(t, pool, sns, res, &signals{})
	insertRow(t, pool, "S-A", 1, "pending", base, nil)
	p.tick(context.Background())
	insertRow(t, pool, "S-A", 2, "pending", base, nil) // 장애 중 완결

	for range 3 {
		makeDue(t, pool)
		p.tick(context.Background())
	}

	msgs := sns.sent()
	if len(msgs) != 2 {
		t.Fatalf("발행 = %d 통, want 2", len(msgs))
	}
	first, second := decode(t, msgs[0]), decode(t, msgs[1])
	if first.EventType != EventStarted || second.EventType != EventEnded || first.StreamerID != "42" || second.StreamerID != "42" {
		t.Errorf("발행 순서 · 값 = %s(%s) → %s(%s), want started(42) → ended(42)",
			first.EventType, first.StreamerID, second.EventType, second.StreamerID)
	}
}

// D63 — 막힌 ended 30개(선행 started 가 아직 때가 안 됨)가 있어도 뒤의 started 가 나간다.
func TestPublisherNotStarvedByBlockedEnded(t *testing.T) {
	pool := newTestPool(t)
	sns := &fakeSNS{}
	p := newPublisher(t, pool, sns, &fakeResolve{answer: validUser}, &signals{})
	old := time.Now().Add(-time.Hour)
	for i := range 30 {
		round := "S-blocked-" + string(rune('a'+i%26)) + string(rune('a'+i/26))
		insertRow(t, pool, round, 1, "pending", time.Now().Add(time.Hour), nil)
		insertRow(t, pool, round, 2, "pending", old.Add(time.Duration(i)*time.Second), nil)
	}
	insertRow(t, pool, "S-fresh", 1, "pending", time.Now().Add(-time.Second), nil)

	p.tick(context.Background())

	if msgs := sns.sent(); len(msgs) != 1 || msgs[0].DeduplicationID != "S-fresh.started" {
		t.Errorf("발행 = %+v, want [S-fresh.started]", msgs)
	}
}

// D46 — resolve 는 장부 트랜잭션 밖이다. 발행기가 resolve 를 기다리는 동안에도 개시 트랜잭션의 outbox INSERT 는
// 기다리지 않는다 — 개시 트랜잭션이 설계 문턱 100ms 안에 끝난다. 세 번 재어 가장 빠른 한 번으로 단언한다. 단발은
// -race 에서 40번에 3번, 10번 평균도 40번에 1번 100ms 를 넘었다 — 연결 확립 · 호스트 정체 같은 결함 밖 지연이
// 재는 구간에 들고, 평균은 시도 전부가 느린 정체를 거르지 못한다. resolve 가 개시를 막으면 어느 시도도 빠를 수
// 없으므로 최솟값이 그것을 가리지 못한다.
// 잡는 결함: resolve 를 outbox 를 잠근 트랜잭션 안에서 부르면 개시(조각 INSERT 핫 경로)가 auth 지연만큼 막힌다.
func TestEnqueueNotBlockedWhileResolving(t *testing.T) {
	const attempts = 3
	pool := newTestPool(t)
	entered, release := make(chan struct{}), make(chan struct{})
	res := &fakeResolve{answer: func(int) (keyresolve.Verdict, error) {
		close(entered)
		<-release
		return keyresolve.Verdict{Valid: true, UserID: 42}, nil
	}}
	p := newPublisher(t, pool, &fakeSNS{}, res, &signals{})
	insertRow(t, pool, "S-A", 1, "pending", base, nil)
	done := make(chan struct{})
	go func() { defer close(done); p.tick(context.Background()) }()
	defer func() { close(release); <-done }()
	<-entered

	fastest := time.Duration(math.MaxInt64)
	for i := range attempts {
		elapsed, err := timeStartedEnqueue(pool, fmt.Sprintf("S-B%d", i))
		if err != nil {
			t.Fatalf("resolve 대기 중 개시 %d = %v, want 바로 성공", i, err)
		}
		fastest = min(fastest, elapsed)
	}

	if fastest > 100*time.Millisecond {
		t.Errorf("개시 트랜잭션 최솟값(%d번) = %v, want 100ms 안", attempts, fastest)
	}
}

// timeStartedEnqueue 는 round 의 started 를 넣는 개시 트랜잭션 하나(Begin · EnqueueStarted · Commit)의 걸린 시간이다.
// 막혀도 1초에서 끊는다.
func timeStartedEnqueue(pool *pgxpool.Pool, round string) (time.Duration, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	start := time.Now()
	tx, err := pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(context.Background())
	if err := EnqueueStarted(ctx, tx, openGate(), round, ingest, base); err != nil {
		return 0, err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// D47 — 진행 중 Publish 가 ctx 로 끊기면 Run 은 곧바로 돌아오고 행은 pending 그대로다(시도 횟수도 그대로). 다음
// 기동이 같은 eventId 로 다시 낸다(중복이면 D35). 그래서 부르는 쪽이 Run 반환을 기다린 뒤 풀을 닫으면 닫힌 풀에
// 문장이 가지 않는다.
func TestRunStopsAndKeepsRowPendingWhenCancelled(t *testing.T) {
	pool := newTestPool(t)
	entered := make(chan struct{})
	var once sync.Once
	o := PublisherOptions{
		Resolve: (&fakeResolve{answer: validUser}).resolve,
		Publish: func(ctx context.Context, m Message) error {
			once.Do(func() { close(entered) })
			<-ctx.Done()
			return ctx.Err()
		},
		Log: slog.New(&signals{}), Interval: 10 * time.Millisecond,
	}
	p, err := NewPublisher(pool, o)
	if err != nil {
		t.Fatalf("NewPublisher: %v", err)
	}
	insertRow(t, pool, "S-A", 1, "pending", base, nil)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- p.Run(ctx) }()
	<-entered

	cancel()

	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Errorf("Run = %v, want context.Canceled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("취소 뒤 2초 안에 Run 이 돌아오지 않았다")
	}
	if r, _ := readOutbox(t, pool, "S-A.started"); r.state != "pending" || r.attempts != 0 || r.lastError != "" {
		t.Errorf("취소 뒤 = %s · attempts %d · %q, want pending · 0 · 없음", r.state, r.attempts, r.lastError)
	}
}

// D48 — 봉투 검사(Encode)에 걸리는 행은 버린다(ERROR reason=too_long). pending 으로 두면 SNS 가 영영 거부해 그
// 회차가 막힌다.
func TestPublisherAbandonsUnencodableRow(t *testing.T) {
	pool := newTestPool(t)
	sns, sig := &fakeSNS{}, &signals{}
	p := newPublisher(t, pool, sns, &fakeResolve{answer: validUser}, sig)
	round := strings.Repeat("a", maxIDLength)
	insertRow(t, pool, round, 1, "pending", base, ptr("42"))

	p.tick(context.Background())

	if r, _ := readOutbox(t, pool, StartedEventID(round)); r.state != "abandoned" || r.lastError != "too_long" {
		t.Errorf("상태 · 사유 = %s · %q, want abandoned · too_long", r.state, r.lastError)
	}
	if len(sns.sent()) != 0 {
		t.Errorf("발행 = %d, want 0", len(sns.sent()))
	}
}

// K10 — streamerId 를 얻을 길이 없으면 미발행 + ERROR 다. resolve 칸이 없는 판(POK-472i 전 · 옛 모드)은
// resolve_unwired, stream_id 가 토큰 꼴이 아니면(로컬 demo 등 — auth 가 답할 수 없다) streamer_invalid 로 버린다.
// ended 인데 started 의 streamerId 가 비면(구조상 없어야 함) streamer_missing 이다.
func TestPublisherAbandonsWithoutStreamer(t *testing.T) {
	tests := []struct {
		name       string
		resolve    *fakeResolve
		seed       func(t *testing.T, pool *pgxpool.Pool)
		eventID    string
		wantReason string
	}{
		{"resolve_칸_없음", nil, func(t *testing.T, pool *pgxpool.Pool) {
			insertRow(t, pool, "S-A", 1, "pending", base, nil)
		}, "S-A.started", "resolve_unwired"},
		{"토큰_꼴_아님", &fakeResolve{answer: validUser}, func(t *testing.T, pool *pgxpool.Pool) {
			if _, err := pool.Exec(context.Background(), `INSERT INTO broadcast_lifecycle_outbox
				(event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, next_at)
				VALUES ('S-A.started', 'S-A', 'broadcast.started', 1, $1, 'demo', $1)`, base); err != nil {
				t.Fatalf("심기: %v", err)
			}
		}, "S-A.started", "streamer_invalid"},
		{"ended_streamer_없음", &fakeResolve{answer: validUser}, func(t *testing.T, pool *pgxpool.Pool) {
			insertRow(t, pool, "S-A", 1, "published", base, nil)
			insertRow(t, pool, "S-A", 2, "pending", base, nil)
		}, "S-A.ended", "streamer_missing"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pool := newTestPool(t)
			sns, sig := &fakeSNS{}, &signals{}
			p := newPublisher(t, pool, sns, tt.resolve, sig)
			tt.seed(t, pool)

			p.tick(context.Background())

			if r, _ := readOutbox(t, pool, tt.eventID); r.state != "abandoned" || r.lastError != tt.wantReason {
				t.Errorf("%s = %s · %q, want abandoned · %q", tt.eventID, r.state, r.lastError, tt.wantReason)
			}
			if tt.resolve != nil && tt.resolve.calls() != 0 {
				t.Errorf("resolve 호출 = %d, want 0", tt.resolve.calls())
			}
			if n := sig.count(slog.LevelError, "lifecycle_abandoned", "reason", tt.wantReason); n != 1 {
				t.Errorf("lifecycle_abandoned reason=%s = %d, want 1", tt.wantReason, n)
			}
			if len(sns.sent()) != 0 {
				t.Errorf("발행 = %d, want 0", len(sns.sent()))
			}
		})
	}
}

// 정리(15.5 규칙 5)는 기동 뒤 첫 회차에 돈다 — ended 가 발행된 지 7일 지난 회차의 행이 지워진다.
func TestPublisherDeletesSettledRounds(t *testing.T) {
	pool := newTestPool(t)
	p := newPublisher(t, pool, &fakeSNS{}, &fakeResolve{answer: validUser}, &signals{})
	insertRow(t, pool, "S-old", 1, "published", base, ptr("1"))
	insertRow(t, pool, "S-old", 2, "published", base, nil)
	if _, err := pool.Exec(context.Background(),
		"UPDATE broadcast_lifecycle_outbox SET published_at = pg_catalog.now() - interval '8 days'"); err != nil {
		t.Fatalf("published_at 되돌리기: %v", err)
	}

	p.tick(context.Background())

	if n := countOutbox(t, pool); n != 0 {
		t.Errorf("outbox 행 = %d, want 0", n)
	}
}

// NewPublisher 는 풀 · 발행 칸이 없으면 거부한다. resolve 칸은 비어도 된다(K10 갈래로 간다).
func TestNewPublisherRejectsMissingParts(t *testing.T) {
	pool := newTestPool(t)
	publish := func(context.Context, Message) error { return nil }
	if _, err := NewPublisher(nil, PublisherOptions{Publish: publish}); err == nil {
		t.Error("NewPublisher(nil 풀) = nil, want 오류")
	}
	if _, err := NewPublisher(pool, PublisherOptions{}); err == nil {
		t.Error("NewPublisher(발행 칸 없음) = nil, want 오류")
	}
	if _, err := NewPublisher(pool, PublisherOptions{Publish: publish}); err != nil {
		t.Errorf("NewPublisher(resolve 없음) = %v, want nil", err)
	}
}
