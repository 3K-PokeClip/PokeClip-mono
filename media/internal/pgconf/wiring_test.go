package pgconf

// 배선 시험(계획 4-2 · 4-3): 가짜 PG 서버와 가짜 TokenFunc 로 BeforeConnect 가 연결마다 불리는지 ·
// 백그라운드 보충 연결에도 시간 상한이 서는지 · 비밀이 오류와 로그에 새지 않는지 · TLS 검증 세 갈래를 잰다.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// testDeadline 은 시험 자체의 상한이다 — 넘으면 BeforeConnect 가 시간 상한 없이 걸렸다는 뜻이다.
const testDeadline = 5 * time.Second

func staticToken(tok string) TokenFunc {
	return func(context.Context, string, string, string) (string, error) { return tok, nil }
}

// tokenCall 은 가짜 TokenFunc 한 번의 기록이다.
type tokenCall struct {
	endpoint, region, user string
}

// countingToken 은 호출마다 순번 토큰(tok-1, tok-2, …)을 내고 인자를 기록한다.
type countingToken struct {
	n     atomic.Int64
	mu    sync.Mutex
	calls []tokenCall
}

func (c *countingToken) fn(_ context.Context, endpoint, region, user string) (string, error) {
	c.mu.Lock()
	c.calls = append(c.calls, tokenCall{endpoint, region, user})
	c.mu.Unlock()
	return fmt.Sprintf("tok-%d", c.n.Add(1)), nil
}

// iamFixture 는 자체 서명 CA 로 TLS 를 켠 가짜 서버와, 그 서버를 가리키는 iam Settings 를 FromEnv 로 만든다
// (sslmode 는 iam 기본값 — 변이 V8 이 이 경로를 지나게).
func iamFixture(t *testing.T, o fakePGOptions) (Settings, *fakePG) {
	t.Helper()
	ca := newTestCA(t, "pgconf-test-ca")
	o.tlsConf = ca.serverTLS(t, "localhost")
	srv := startFakePG(t, o)
	caFile := writeTestFile(t, t.TempDir(), "ca.pem", ca.pem)
	s, err := FromEnv(envOf(with(iamEnv(), "POSTGRES_HOST", "localhost", "POSTGRES_PORT", srv.portString(), "POSTGRES_SSLROOTCERT", caFile)))
	if err != nil {
		t.Fatal(err)
	}
	return s, srv
}

func newPool(t *testing.T, pc *pgxpool.Config) *pgxpool.Pool {
	t.Helper()
	pool, err := pgxpool.NewWithConfig(context.Background(), pc)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// 사례 1 · 2: 연결마다 새 토큰을 만들고(캐시 없음 — E2), 인자는 host:port · region · user 그대로다.
func TestIAMTokenIsFreshPerConnection(t *testing.T) {
	s, srv := iamFixture(t, fakePGOptions{})
	tok := &countingToken{}
	pc, err := PoolConfig(context.Background(), s, WithTokenFunc(tok.fn))
	if err != nil {
		t.Fatal(err)
	}
	pool := newPool(t, pc)
	ctx, cancel := context.WithTimeout(context.Background(), testDeadline)
	defer cancel()
	c1, err := pool.Acquire(ctx)
	if err != nil {
		t.Fatalf("첫 연결 실패: %v", err)
	}
	c2, err := pool.Acquire(ctx) // c1 을 쥔 채라 새 연결이다
	if err != nil {
		t.Fatalf("둘째 연결 실패: %v", err)
	}
	c1.Release()
	c2.Release()

	got := srv.received()
	if len(got) != 2 || got[0] == got[1] {
		t.Fatalf("서버가 받은 비밀번호 = %q, want 서로 다른 토큰 둘", got)
	}
	for _, p := range got {
		if !strings.HasPrefix(p, "tok-") {
			t.Errorf("서버가 받은 비밀번호 %q 가 TokenFunc 결과가 아니다", p)
		}
	}
	want := tokenCall{endpoint: fmt.Sprintf("localhost:%d", srv.port()), region: "ap-northeast-2", user: "media_indexer"}
	tok.mu.Lock()
	defer tok.mu.Unlock()
	for i, c := range tok.calls {
		if c != want {
			t.Errorf("호출 %d 인자 = %+v, want %+v", i, c, want)
		}
	}
}

// 사례 3(E1 · pgx#2545): 풀의 헬스체크 보충 연결은 context.Background 로 BeforeConnect 를 부른다. 토큰 함수가
// ctx 끝까지 막혀도 자체 시간 상한 안에 돌아와야 한다. 반환 시각은 채널로 받고(D12), Cleanup 의 pool.Close 는
// 고루틴으로 돌린다 — 변이로 BeforeConnect 가 영영 걸리면 동기 Close 가 시험을 매달기 때문이다.
func TestIAMTokenTimeoutHoldsOnBackgroundRefill(t *testing.T) {
	s, _ := iamFixture(t, fakePGOptions{})
	const timeout = 200 * time.Millisecond
	type ret struct {
		took time.Duration
		err  error
	}
	returns := make(chan ret, 64)
	blocking := func(ctx context.Context, _, _, _ string) (string, error) {
		start := time.Now()
		<-ctx.Done()
		returns <- ret{took: time.Since(start), err: ctx.Err()}
		return "", ctx.Err()
	}
	pc, err := PoolConfig(context.Background(), s, WithTokenFunc(blocking), WithTokenTimeout(timeout))
	if err != nil {
		t.Fatal(err)
	}
	pc.MinConns = 1
	pc.HealthCheckPeriod = 50 * time.Millisecond

	// 첫 MinConns 채우기는 NewWithConfig 의 ctx 를 쓴다(pgxpool pool.go:334-335). 그 ctx 를 바로 거둬
	// 첫 채우기를 끝내면, 남는 호출은 헬스체크 보충(pool.go:563 — Background)뿐이다.
	createCtx, cancelCreate := context.WithCancel(context.Background())
	pool, err := pgxpool.NewWithConfig(createCtx, pc)
	cancelCreate()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { go pool.Close() })

	deadline := time.After(testDeadline)
	for {
		select {
		case r := <-returns:
			if !errors.Is(r.err, context.DeadlineExceeded) {
				continue // 첫 채우기(취소된 ctx)의 호출
			}
			if r.took > timeout+time.Second {
				t.Fatalf("보충 연결의 토큰 생성이 %v 걸렸다, want ≤ %v + 여유", r.took, timeout)
			}
			return
		case <-deadline:
			t.Fatalf("시험 상한 %v 초과 — Background 경로의 토큰 생성에 시간 상한이 없다", testDeadline)
		}
	}
}

// 시간 초과면 오류 문구가 시간 초과를 말한다(원인 오류는 싣지 않는다).
func TestIAMTokenTimeoutErrorNamesTimeout(t *testing.T) {
	s, _ := iamFixture(t, fakePGOptions{})
	blocking := func(ctx context.Context, _, _, _ string) (string, error) {
		<-ctx.Done()
		return "", fmt.Errorf("%s: %w", canary, ctx.Err())
	}
	pc, err := PoolConfig(context.Background(), s, WithTokenFunc(blocking), WithTokenTimeout(50*time.Millisecond))
	if err != nil {
		t.Fatal(err)
	}
	err = pc.BeforeConnect(context.Background(), pc.ConnConfig.Copy())
	if err == nil || !strings.Contains(err.Error(), "시간 초과") {
		t.Fatalf("BeforeConnect 에러 = %v, want 시간 초과", err)
	}
	if strings.Contains(err.Error(), canary) {
		t.Error("에러에 카나리아 발견")
	}
}

// 사례 4: 값 없는 ctx(Background)로도 돈다 — ctx 의 값에 기대지 않는다(pgx#2545 증상).
func TestIAMBeforeConnectWorksWithBareContext(t *testing.T) {
	s, _ := iamFixture(t, fakePGOptions{})
	pc, err := PoolConfig(context.Background(), s, WithTokenFunc(staticToken("bare")))
	if err != nil {
		t.Fatal(err)
	}
	cc := pc.ConnConfig.Copy()
	if err := pc.BeforeConnect(context.Background(), cc); err != nil {
		t.Fatalf("BeforeConnect 에러 %v", err)
	}
	if cc.Password != "bare" {
		t.Errorf("연결 설정 비밀번호가 토큰이 아니다(길이 %d)", len(cc.Password))
	}
	if pc.ConnConfig.Password != "" {
		t.Error("BeforeConnect 가 풀 원본 설정에 토큰을 남겼다")
	}
}

// 사례 5(E8): 동시 획득 8개 — 연결마다 서로 다른 토큰. -race 로 돌린다.
func TestIAMConcurrentAcquiresGetDistinctTokens(t *testing.T) {
	s, srv := iamFixture(t, fakePGOptions{})
	tok := &countingToken{}
	pc, err := PoolConfig(context.Background(), s, WithTokenFunc(tok.fn))
	if err != nil {
		t.Fatal(err)
	}
	const n = 8
	pc.MaxConns = n
	pool := newPool(t, pc)
	ctx, cancel := context.WithTimeout(context.Background(), testDeadline)
	defer cancel()

	var wg sync.WaitGroup
	hold := make(chan struct{})
	errs := make(chan error, n)
	for range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c, err := pool.Acquire(ctx)
			if err != nil {
				errs <- err
				return
			}
			defer c.Release()
			if err := c.Ping(ctx); err != nil {
				errs <- err
			}
			<-hold // 모두 쥔 채로 기다려 연결 8개가 따로 생기게 한다
		}()
	}
	waitFor(t, func() bool { return len(srv.received()) == n })
	close(hold)
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Errorf("획득 · Ping 실패: %v", err)
	}
	seen := map[string]bool{}
	for _, p := range srv.received() {
		seen[p] = true
	}
	if len(seen) != n {
		t.Errorf("서로 다른 토큰 %d 개, want %d", len(seen), n)
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(testDeadline)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("시험 상한 %v 안에 조건이 서지 않았다", testDeadline)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// 사례 6(E3 · D1): 토큰 함수의 원인 오류(자격 증명 도구 출력 등)는 최종 오류 · 로그에서 사라진다.
// %w 사슬로도 닿지 않는다. 떠도는 PGPASSWORD 는 pgx 가 iam DSN 의 빈 비밀번호 자리에 읽어 두므로(E6) 그 값도
// 카나리아로 두어, 실패 오류가 연결 설정의 비밀번호를 싣는지까지 본다(변이 V6).
func TestIAMTokenErrorDropsCause(t *testing.T) {
	t.Setenv("PGPASSWORD", canary)
	s, _ := iamFixture(t, fakePGOptions{})
	cause := errors.New("credential_process output: " + canary)
	failing := func(context.Context, string, string, string) (string, error) { return "", cause }
	pc, err := PoolConfig(context.Background(), s, WithTokenFunc(failing))
	if err != nil {
		t.Fatal(err)
	}
	pool := newPool(t, pc)
	var logs bytes.Buffer
	ctx, cancel := context.WithTimeout(context.Background(), testDeadline)
	defer cancel()
	err = ConfirmConnection(ctx, pool, s, slog.New(slog.NewJSONHandler(&logs, nil)), "indexer")
	if err == nil {
		t.Fatal("ConfirmConnection 에러 nil, want 토큰 실패")
	}
	if strings.Contains(err.Error(), canary) || strings.Contains(logs.String(), canary) {
		t.Error("오류 · 로그에 카나리아 발견")
	}
	if errors.Is(err, cause) {
		t.Error("최종 오류의 %w 사슬에 원인 오류가 남았다")
	}
	for _, want := range []string{"RDS IAM 토큰", "ap-northeast-2", "media_indexer"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("오류 %q 에 %q 가 없다", err, want)
		}
	}
	if strings.Contains(logs.String(), ConnectedEvent) {
		t.Error("실패했는데 연결 성공 줄이 남았다")
	}
}

// 사례 6 의 다른 갈래: 서버가 토큰을 거부해도 오류에 토큰이 실리지 않는다.
func TestRejectedTokenNotInError(t *testing.T) {
	s, _ := iamFixture(t, fakePGOptions{accept: func(string) bool { return false }})
	pc, err := PoolConfig(context.Background(), s, WithTokenFunc(staticToken(canary)))
	if err != nil {
		t.Fatal(err)
	}
	pool := newPool(t, pc)
	var logs bytes.Buffer
	ctx, cancel := context.WithTimeout(context.Background(), testDeadline)
	defer cancel()
	err = ConfirmConnection(ctx, pool, s, slog.New(slog.NewJSONHandler(&logs, nil)), "indexer")
	if err == nil {
		t.Fatal("ConfirmConnection 에러 nil, want 인증 거부")
	}
	if strings.Contains(err.Error(), canary) || strings.Contains(logs.String(), canary) {
		t.Error("오류 · 로그에 카나리아 발견")
	}
}

// 사례 7: password 모드는 BeforeConnect 없이 env 비밀번호로 붙는다(지금과 같음).
func TestPasswordModeSendsEnvPassword(t *testing.T) {
	srv := startFakePG(t, fakePGOptions{})
	s, err := FromEnv(envOf(with(passwordEnv(), "POSTGRES_HOST", "127.0.0.1", "POSTGRES_PORT", srv.portString(), "POSTGRES_SSLMODE", "disable", "POSTGRES_PASSWORD", "p@ss word")))
	if err != nil {
		t.Fatal(err)
	}
	pc, err := PoolConfig(context.Background(), s)
	if err != nil {
		t.Fatal(err)
	}
	pool := newPool(t, pc)
	ctx, cancel := context.WithTimeout(context.Background(), testDeadline)
	defer cancel()
	if err := pool.Ping(ctx); err != nil {
		t.Fatalf("Ping 실패: %v", err)
	}
	if got := srv.received(); len(got) != 1 || got[0] != "p@ss word" {
		t.Errorf("서버가 받은 비밀번호가 env 값이 아니다(%d 건)", len(got))
	}
}

// connectedLines 는 로그 버퍼에서 연결 성공 줄만 골라 해석한다.
func connectedLines(t *testing.T, logs *bytes.Buffer) []map[string]any {
	t.Helper()
	var out []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(logs.String()), "\n") {
		if line == "" {
			continue
		}
		var m map[string]any
		if err := json.Unmarshal([]byte(line), &m); err != nil {
			t.Fatalf("로그 줄 해석 실패: %v", err)
		}
		if m["msg"] == ConnectedEvent {
			out = append(out, m)
		}
	}
	return out
}

// 사례 8: Ping 이 성공하면 연결 성공 줄 하나 — 필드는 설정값과 실제 TLS 여부.
func TestConfirmConnectionLogsOneLine(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), testDeadline)
	defer cancel()

	t.Run("iam · TLS", func(t *testing.T) {
		s, srv := iamFixture(t, fakePGOptions{})
		pc, err := PoolConfig(ctx, s, WithTokenFunc(staticToken("t")))
		if err != nil {
			t.Fatal(err)
		}
		var logs bytes.Buffer
		if err := ConfirmConnection(ctx, newPool(t, pc), s, slog.New(slog.NewJSONHandler(&logs, nil)), "indexer"); err != nil {
			t.Fatalf("ConfirmConnection 에러 %v", err)
		}
		lines := connectedLines(t, &logs)
		if len(lines) != 1 {
			t.Fatalf("연결 성공 줄 %d 개, want 1", len(lines))
		}
		want := map[string]any{"level": "INFO", "pool": "indexer", "auth": "iam", "sslmode": "verify-full", "tls": true, "user": "media_indexer", "host": "localhost", "port": float64(srv.port()), "db": "pokeclip"}
		for k, v := range want {
			if lines[0][k] != v {
				t.Errorf("필드 %s = %v, want %v", k, lines[0][k], v)
			}
		}
	})
	t.Run("password · 평문", func(t *testing.T) {
		srv := startFakePG(t, fakePGOptions{})
		s, err := FromEnv(envOf(with(passwordEnv(), "POSTGRES_HOST", "127.0.0.1", "POSTGRES_PORT", srv.portString(), "POSTGRES_PASSWORD", canary)))
		if err != nil {
			t.Fatal(err)
		}
		pc, err := PoolConfig(ctx, s)
		if err != nil {
			t.Fatal(err)
		}
		var logs bytes.Buffer
		if err := ConfirmConnection(ctx, newPool(t, pc), s, slog.New(slog.NewJSONHandler(&logs, nil)), "janitor"); err != nil {
			t.Fatalf("ConfirmConnection 에러 %v", err)
		}
		lines := connectedLines(t, &logs)
		if len(lines) != 1 {
			t.Fatalf("연결 성공 줄 %d 개, want 1", len(lines))
		}
		// prefer 는 서버가 TLS 를 거절하면 평문으로 내려간다 — 줄의 tls 가 설정값이 아니라 실제를 말해야 한다.
		if lines[0]["tls"] != false || lines[0]["sslmode"] != "prefer" || lines[0]["auth"] != "password" || lines[0]["pool"] != "janitor" {
			t.Errorf("줄 = %v, want tls=false · sslmode=prefer · auth=password · pool=janitor", lines[0])
		}
		if strings.Contains(logs.String(), canary) {
			t.Error("로그에 카나리아 발견")
		}
	})
}

// 사례 8 실패 갈래: 연결은 되지만 Ping 이 실패하면 줄 없이 오류다(변이 V9 — 줄을 Ping 앞에 쓰면 FAIL).
func TestConfirmConnectionNoLineWhenPingFails(t *testing.T) {
	s, _ := iamFixture(t, fakePGOptions{failQuery: true})
	pc, err := PoolConfig(context.Background(), s, WithTokenFunc(staticToken("t")))
	if err != nil {
		t.Fatal(err)
	}
	var logs bytes.Buffer
	ctx, cancel := context.WithTimeout(context.Background(), testDeadline)
	defer cancel()
	if err := ConfirmConnection(ctx, newPool(t, pc), s, slog.New(slog.NewJSONHandler(&logs, nil)), "indexer"); err == nil {
		t.Fatal("ConfirmConnection 에러 nil, want Ping 실패")
	}
	if n := len(connectedLines(t, &logs)); n != 0 {
		t.Errorf("Ping 이 실패했는데 연결 성공 줄 %d 개", n)
	}
}

// TLS 세 갈래(계획 4-3 · E7): 같은 CA + 이름 일치만 성공. IP 로 붙거나(SAN 불일치) 다른 CA 면 x509 로 거부.
func TestIAMVerifyFullBranches(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), testDeadline)
	defer cancel()
	connect := func(t *testing.T, s Settings) error {
		t.Helper()
		pc, err := PoolConfig(ctx, s, WithTokenFunc(staticToken("t")))
		if err != nil {
			t.Fatal(err)
		}
		return newPool(t, pc).Ping(ctx)
	}

	t.Run("같은 CA · 이름 일치", func(t *testing.T) {
		s, _ := iamFixture(t, fakePGOptions{})
		if err := connect(t, s); err != nil {
			t.Fatalf("Ping 실패: %v", err)
		}
	})
	t.Run("IP 로 접속(SAN 불일치)", func(t *testing.T) {
		s, srv := iamFixture(t, fakePGOptions{})
		s.Host = "127.0.0.1"
		err := connect(t, s)
		if err == nil || !strings.Contains(err.Error(), "x509") {
			t.Fatalf("Ping 에러 = %v, want x509 호스트 거부", err)
		}
		if n := len(srv.received()); n != 0 {
			t.Errorf("검증 실패 뒤에도 토큰 %d 건이 서버로 갔다", n)
		}
	})
	t.Run("다른 CA", func(t *testing.T) {
		s, srv := iamFixture(t, fakePGOptions{})
		other := newTestCA(t, "other-ca")
		s.SSLRootCert = writeTestFile(t, t.TempDir(), "other.pem", other.pem)
		err := connect(t, s)
		if err == nil || !strings.Contains(err.Error(), "unknown authority") {
			t.Fatalf("Ping 에러 = %v, want unknown authority", err)
		}
		if n := len(srv.received()); n != 0 {
			t.Errorf("검증 실패 뒤에도 토큰 %d 건이 서버로 갔다", n)
		}
	})
}
