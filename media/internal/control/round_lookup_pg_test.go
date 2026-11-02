package control

// NewRoundLookup 의 실 PG 시험(POK-196 M6-3b 커밋 ⑤ · 설계 M6 11절 · 계약3 7-2절 회차별 조회 응답 규칙 ①–⑤). 회차 경로
// 처리기(RoundRoute)에 실제 장부를 읽는 lookup 을 붙여 HTTP 로 판정한다.

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// TestMain 은 릴리스 게이트용 스위치다(index/testdb_test.go 와 같은 이디엄). PG 케이스는 PG_DSN 이 없으면 skip 되는데
// skip 은 성공으로 집계된다 — REQUIRE_PG=1 이면 그 상황을 실패로 바꾼다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — 제어면 회차 조회 PG 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

// newLookupPool 은 이 패키지 전용 테스트 DB 에 붙은 풀이다(접미 control).
func newLookupPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "control", index.EnsureSchema, func(ctx context.Context, pool *pgxpool.Pool) error {
		_, err := pool.Exec(ctx, "TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions, stream_seq_floors, broadcast_lifecycle_outbox")
		return err
	})
}

// plantSession 은 회차 칸까지 심는 세션 행이다. ended 면 회차를 닫고 VOD 칸을 채운다(key 가 빈 값이면 VOD 칸 없음).
func plantSession(t *testing.T, pool *pgxpool.Pool, id, stream, state, roundID, key string, first, last int64, started time.Time) {
	t.Helper()
	var endingAt, endedAt, closedAt, vodKey, vodFirst, vodLast any
	vodState := "none"
	if state != "live" {
		endingAt = started.Add(time.Minute)
	}
	if state == "ended" {
		endedAt, closedAt = started.Add(2*time.Minute), started.Add(2*time.Minute)
	}
	if key != "" {
		vodState, vodKey, vodFirst, vodLast = "frozen", key, first, last
	}
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_sessions
    (session_id, stream_id, started_at, state, ending_at, ended_at, round_id, round_closed_at, vod_state, vod_s3_key,
     vod_first_seq, vod_last_seq)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
		id, stream, started, state, endingAt, endedAt, roundID, closedAt, vodState, vodKey, vodFirst, vodLast); err != nil {
		t.Fatalf("세션 픽스처 실패 %s: %v", id, err)
	}
}

// plantSegment 는 VOD 범위의 조각 한 행이다.
func plantSegment(t *testing.T, pool *pgxpool.Pool, stream, session string, seq int64, pdt time.Time, durMS int32) {
	t.Helper()
	if _, err := pool.Exec(context.Background(), `
INSERT INTO stream_segments
    (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, local_path, upload_state, bytes, session_id, playback_pdt)
VALUES ($1, $2, 0, $3, $4, $5, $6, 'uploaded', 1000, $7, $3)`,
		stream, seq, pdt, durMS, fmt.Sprintf("k-%s-%d", stream, seq), fmt.Sprintf("/recordings/%s/%d.mp4", stream, seq), session); err != nil {
		t.Fatalf("조각 픽스처 실패 %s/%d: %v", stream, seq, err)
	}
}

// 응답 규칙 ①–⑤ 를 실 장부로 본다. 스트림 s 에 회차 둘(R1 완결 VOD · R2 송출 중)을 두면 R1 은 vod · R2 는 live 이고 서로
// 섞이지 않는다(②). VOD 범위는 그 회차 사슬 조각에서 온다(③). 모르는 회차 · 다른 스트림의 회차는 404(④). VOD 키는 있는데
// 범위 조각이 없는 회차는 변환 오류라 503 · ERROR 한 줄이다(4 의 「잠시 답할 수 없음」 — 「다시보기 없음」으로 접지 않는다).
func TestNewRoundLookupAnswersFromLedger(t *testing.T) {
	pool := newLookupPool(t)
	start := time.Now().UTC().Add(-3 * time.Hour).Truncate(time.Millisecond)
	plantSession(t, pool, "S1", "s", "ended", "R1", "vod/s/R1/index.m3u8", 1, 2, start)
	plantSegment(t, pool, "s", "S1", 1, start, 4000)
	plantSegment(t, pool, "s", "S1", 2, start.Add(4*time.Second), 3000)
	plantSession(t, pool, "S2", "s", "live", "R2", "", 0, 0, start.Add(time.Hour))
	plantSession(t, pool, "U1", "u", "ended", "R3", "vod/u/R3/index.m3u8", 10, 11, start)

	f := newFixture(100)
	f.store(true, nil)
	base := f.start(t, RoundRoute(f.holder, f.opt, NewRoundLookup(pool, lookupBase, f.opt.Log)))

	vod := fmt.Sprintf(`{"ready":true,"mode":"vod","url":"%s/vod/s/R1/index.m3u8","firstPdt":"%s","headPdt":"%s"}`,
		lookupBase, start.Format("2006-01-02T15:04:05.000Z"), start.Add(7*time.Second).Format("2006-01-02T15:04:05.000Z"))
	for _, tt := range []struct {
		stream, round string
		wantCode      int
		wantBody      string
	}{
		{"s", "R1", http.StatusOK, vod},
		{"s", "R2", http.StatusOK, `{"ready":false,"mode":"live","url":null,"firstPdt":null,"headPdt":null}`},
		{"s", "nope", http.StatusNotFound, `{"error":"not_found"}`},
		{"t", "R1", http.StatusNotFound, `{"error":"not_found"}`},
		{"u", "R3", http.StatusServiceUnavailable, `{"error":"unavailable"}`},
	} {
		got := get(t, roundURL(base, tt.stream, tt.round))
		if got.code != tt.wantCode || strings.TrimSpace(got.body) != tt.wantBody {
			t.Errorf("%s/%s = (%d, %s), want (%d, %s)", tt.stream, tt.round, got.code, got.body, tt.wantCode, tt.wantBody)
		}
	}
	lines := f.logs.events("control_round_lookup_invalid")
	if len(lines) != 1 || lines[0]["level"] != "ERROR" || lines[0]["stream_id"] != "u" || lines[0]["round_id"] != "R3" ||
		lines[0]["reason"] != "vod_incomplete" {
		t.Errorf("control_round_lookup_invalid %v, want u/R3 vod_incomplete ERROR 한 줄", lines)
	}
}

// blockingTracer 는 회차 조회 문장을 release 가 닫힐 때까지 붙잡고 동시에 붙잡힌 수의 최댓값을 센다.
type blockingTracer struct {
	release             chan struct{}
	inFlight, maxFlight atomic.Int64
}

func (b *blockingTracer) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	if !strings.Contains(d.SQL, "WITH targets AS (SELECT $1::text AS stream_id") {
		return ctx
	}
	n := b.inFlight.Add(1)
	for m := b.maxFlight.Load(); n > m && !b.maxFlight.CompareAndSwap(m, n); m = b.maxFlight.Load() {
	}
	select {
	case <-b.release:
	case <-ctx.Done():
	}
	return context.WithValue(ctx, b, true)
}

func (b *blockingTracer) TraceQueryEnd(ctx context.Context, _ *pgx.Conn, _ pgx.TraceQueryEndData) {
	if ctx.Value(b) != nil {
		b.inFlight.Add(-1)
	}
}

// 설계 11절 「풀은 인덱서 풀 · 동시 2」: 서로 다른 회차 10건이 한꺼번에 와도 DB 에 동시에 나가는 회차 조회는 둘이다.
// 나머지 여덟은 기다리지 않고 503 이다(전역 예산 — 풀 연결을 줄 서서 잡지 않는다). 그래서 인덱서 풀(6)의 핫 경로 몫
// 셋 · 4D 몫 하나가 회차 조회 폭주에 밀리지 않는다. 풀어 준 둘은 끝까지 답한다.
func TestNewRoundLookupHoldsAtMostTwoQueries(t *testing.T) {
	tracer := &blockingTracer{release: make(chan struct{})}
	pool := pgtest.TracedPool(t.Context(), t, newLookupPool(t), tracer)
	f := newFixture(100)
	f.opt.RoundLimits.Timeout = testWait // 붙잡은 동안 2초 시한이 먼저 끊지 않게 — 동시 상한은 운영 값 그대로다
	f.store(true, nil)
	base := f.start(t, RoundRoute(f.holder, f.opt, NewRoundLookup(pool, lookupBase, f.opt.Log)))

	const n = 10
	codes := make(chan int, n)
	var wg sync.WaitGroup
	for i := range n {
		wg.Go(func() { codes <- get(t, roundURL(base, "s", "R"+strconv.Itoa(i))).code })
	}
	shed := 0
	for shed < n-2 {
		select {
		case c := <-codes:
			if c != http.StatusServiceUnavailable {
				t.Fatalf("붙잡힌 동안의 답 = %d, want 503(예산 거부)", c)
			}
			shed++
		case <-time.After(testWait):
			t.Fatalf("503 %d건 뒤 멈췄다 — 붙잡힌 조회 %d", shed, tracer.inFlight.Load())
		}
	}
	// 예산을 받은 둘은 연결을 여는 중일 수 있다 — 둘 다 문장 자리에 닿을 때까지 기다린 뒤 푼다.
	for deadline := time.Now().Add(testWait); tracer.inFlight.Load() < 2; time.Sleep(time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatalf("붙잡힌 회차 조회 = %d, want 2", tracer.inFlight.Load())
		}
	}
	close(tracer.release)
	wg.Wait()
	close(codes)
	for c := range codes {
		if c != http.StatusNotFound {
			t.Errorf("풀린 조회의 답 = %d, want 404(모르는 회차)", c)
		}
	}
	if m := tracer.maxFlight.Load(); m != 2 {
		t.Errorf("동시 회차 조회 최대 = %d, want 2", m)
	}
}
