package credrelay

// 겹친 요청 시험(c2 cc · cx). 원천 호출 하나가 막혀 있는 동안 겹친 요청이 그 호출을 기다리지 않고 그때의 L 로
// 답하는지, 원천을 한 번만 부르는지 본다. -race 로 돌려 캐시 상태의 경합도 함께 본다.

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
)

// overlapRequests 는 한 번에 겹쳐 보내는 요청 수다.
const overlapRequests = 8

// gateSource 는 holdFrom 번째(1부터) 호출부터 entered 에 알리고 release 가 닫힐 때까지 기다린 뒤 자격을 내는
// 원천이다. 그 앞의 호출은 바로 낸다.
type gateSource struct {
	creds    aws.Credentials
	holdFrom int32
	entered  chan struct{}
	release  chan struct{}
	once     sync.Once
	calls    atomic.Int32
}

// newGateSource 는 만료가 expires 인 자격을 내는 원천을 만든다. 시험이 끝나면 막힌 호출을 푼다.
func newGateSource(t *testing.T, expires time.Time, holdFrom int32) *gateSource {
	t.Helper()
	g := &gateSource{
		creds:    aws.Credentials{AccessKeyID: canaryAccessKey, SecretAccessKey: canarySecretKey, SessionToken: canarySessionToken, CanExpire: true, Expires: expires},
		holdFrom: holdFrom,
		entered:  make(chan struct{}, 2*overlapRequests),
		release:  make(chan struct{}),
	}
	t.Cleanup(g.open)
	return g
}

func (g *gateSource) Retrieve(context.Context) (aws.Credentials, error) {
	if g.calls.Add(1) >= g.holdFrom {
		g.entered <- struct{}{}
		<-g.release
	}
	return g.creds, nil
}

// open 은 막힌 호출을 모두 푼다.
func (g *gateSource) open() { g.once.Do(func() { close(g.release) }) }

// waitEntered 는 원천 호출 하나가 막힌 자리에 들어올 때까지 기다린다.
func (g *gateSource) waitEntered(t *testing.T) {
	t.Helper()
	select {
	case <-g.entered:
	case <-time.After(5 * time.Second):
		t.Fatal("원천 호출이 5초 안에 시작되지 않았다")
	}
}

// fireOverlapped 는 h 에 자격 요청 overlapRequests 개를 한꺼번에 보내고, 끝나는 대로 상태 코드를 codes 에 낸다.
func fireOverlapped(h http.Handler, codes chan<- int) {
	for range overlapRequests {
		go func() { codes <- serveCode(h) }()
	}
}

// serveCode 는 h 에 indexer 토큰으로 자격 요청 하나를 보내고 상태 코드를 돌려준다.
func serveCode(h http.Handler) int {
	req := httptest.NewRequest(http.MethodGet, CredentialsPath, nil)
	req.Header.Set("Authorization", canaryIndexerToken)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec.Code
}

// collectCodes 는 codes 에서 n 개를 받는다. 5초 안에 오지 않으면 겹친 요청이 원천 호출을 기다린 것이다.
func collectCodes(t *testing.T, codes <-chan int, n int) map[int]int {
	t.Helper()
	got := map[int]int{}
	for i := 0; i < n; i++ {
		select {
		case c := <-codes:
			got[c]++
		case <-time.After(5 * time.Second):
			t.Fatalf("겹친 요청 %d개 가운데 %d개만 끝났다(%v) — 원천 호출을 기다렸다", n, i, got)
		}
	}
	return got
}

// overlap 은 h 에 요청 묶음을 둘 보낸다 — 첫 묶음은 한꺼번에(원천을 부를 쪽을 다툰다), 둘째 묶음은 원천 호출
// 하나가 막힌 뒤에. 원천 호출이 풀리기 전에 끝난 요청들과 마지막 하나의 상태 코드를 돌려준다.
func overlap(t *testing.T, h http.Handler, src *gateSource) (waiting, last map[int]int) {
	t.Helper()
	codes := make(chan int, 2*overlapRequests)
	fireOverlapped(h, codes)
	src.waitEntered(t)
	fireOverlapped(h, codes)
	waiting = collectCodes(t, codes, 2*overlapRequests-1)
	src.open()
	return waiting, collectCodes(t, codes, 1)
}

// 첫 호출이 겹치면 원천은 한 번만 부르고, 그 호출이 끝나기 전의 나머지는 줄 자격이 없어 503 이다(설계 2.5 —
// 기동 직후 cred_unavailable 은 겹친 첫 요청일 수 있다). 원천을 부른 하나만 200 이다.
func TestOverlappedFirstCallsShareOneSourceCall(t *testing.T) {
	clock := newManualClock()
	src := newGateSource(t, clock.start.Add(6*time.Hour), 1)
	h := NewHandler(src, loadTestTokens(t), clock.now, newTestLogger(&logBuf{}))

	waiting, last := overlap(t, h, src)
	if waiting[http.StatusServiceUnavailable] != 2*overlapRequests-1 || last[http.StatusOK] != 1 {
		t.Errorf("원천 호출 중 끝난 요청 %v · 마지막 %v, want 503 %d개 · 200 하나", waiting, last, 2*overlapRequests-1)
	}
	if n := src.calls.Load(); n != 1 {
		t.Errorf("원천 호출 %d번, want 1", n)
	}
}

// L 이 있을 때 다시 읽기(30분)가 겹치면 원천은 한 번만 부르고, 나머지는 그 호출을 기다리지 않고 L 로 200 이다.
func TestOverlappedRefreshServesLastValid(t *testing.T) {
	clock := newManualClock()
	src := newGateSource(t, clock.start.Add(6*time.Hour), 2)
	h := NewHandler(src, loadTestTokens(t), clock.now, newTestLogger(&logBuf{}))
	if code := serveCode(h); code != http.StatusOK {
		t.Fatalf("첫 요청 %d, want 200", code)
	}
	clock.advance(periodicReread)

	waiting, last := overlap(t, h, src)
	if waiting[http.StatusOK] != 2*overlapRequests-1 || last[http.StatusOK] != 1 {
		t.Errorf("다시 읽기 중 끝난 요청 %v · 마지막 %v, want 모두 200", waiting, last)
	}
	if n := src.calls.Load(); n != 2 {
		t.Errorf("원천 호출 %d번, want 2(첫 요청 + 겹친 다시 읽기 하나)", n)
	}
}
