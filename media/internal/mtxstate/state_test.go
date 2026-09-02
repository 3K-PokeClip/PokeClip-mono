package mtxstate

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// ---------------------------------------------------------------------------
// 픽스처 — 응답 JSON 은 F-34 실측 원문 그대로다.
//
// 손으로 줄인 JSON 을 쓰면 "우리가 상상한 응답"을 파싱하는 테스트가 된다.
// 아래 세 상수는 2026-09-02 실측(_workspace/04_mtxapi_probe_raw.md)의 본문을 한 글자도
// 고치지 않고 옮긴 것이다 — 필드 순서·소수 자릿수·deprecated 필드까지 그대로다.
// ---------------------------------------------------------------------------

// 실측 3 — RTMP 송출 중. online/onlineTime·ready/readyTime·available/availableTime 이 모두 실재한다.
const rawPublishing = `{"itemCount":1,"pageCount":1,"items":[{"name":"probe-stream","confName":"all_others","ready":true,"readyTime":"2026-09-01T16:25:40.788540645Z","available":true,"availableTime":"2026-09-01T16:25:40.788540645Z","online":true,"onlineTime":"2026-09-01T16:25:40.78854881Z","source":{"type":"rtmpConn","id":"7b2e8365-e080-4b34-b87f-ada79ac77866"},"tracks":["H264","MPEG-4 Audio"],"tracks2":[{"codec":"H264","codecProps":{"width":320,"height":240,"profile":"High 4:4:4 Predictive","level":"1.3"}},{"codec":"MPEG-4 Audio","codecProps":{"sampleRate":44100,"channelCount":1}}],"readers":[],"inboundBytes":262281,"outboundBytes":0,"inboundFramesInError":0,"bytesReceived":262281,"bytesSent":0}]}`

// 실측 2·4 — 무송출/송출 종료 후. all_others 동적 경로는 항목이 통째로 사라진다.
const rawEmpty = `{"itemCount":0,"pageCount":0,"items":[]}`

// probeStream 은 실측 응답에 담긴 경로 이름이다. 우리 어휘로는 streamID 다.
const probeStream = "probe-stream"

// probeOnlineTime 은 실측 응답의 onlineTime 이다(에폭 tier ⓘ 의 원천).
var probeOnlineTime = time.Date(2026, 9, 1, 16, 25, 40, 788548810, time.UTC)

// logCapture 는 "침묵 실패가 아님"을 테스트가 직접 확인할 수 있게 한다.
// (internal/mtxhook·internal/indexer 의 같은 이름 헬퍼와 같은 형태다.)
type logCapture struct {
	mu      sync.Mutex
	records []slog.Record
}

func (c *logCapture) Enabled(context.Context, slog.Level) bool { return true }

func (c *logCapture) Handle(_ context.Context, r slog.Record) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.records = append(c.records, r)
	return nil
}

func (c *logCapture) WithAttrs([]slog.Attr) slog.Handler { return c }
func (c *logCapture) WithGroup(string) slog.Handler      { return c }

// countReason 은 msg 신호 중 reason 라벨이 일치하는 건수를 센다.
// 라벨까지 보는 이유: 사유가 뒤바뀌어도 건수만 세면 통과한다.
func (c *logCapture) countReason(msg, reason string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.records {
		if r.Message != msg {
			continue
		}
		r.Attrs(func(a slog.Attr) bool {
			if a.Key == "reason" && a.Value.String() == reason {
				n++
			}
			return true
		})
	}
	return n
}

// levels 는 그 신호가 실린 등급을 발화 순서대로 준다. 등급도 계약이라 따로 잰다
// (s4_signal_grades): Info 로 내려가면 운영자가 보는 경보 축에서 통째로 사라지고,
// Error 로 올라가면 자연 회복하는 순간 장애가 사람을 부른다.
func (c *logCapture) levels(msg string) []slog.Level {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out []slog.Level
	for _, r := range c.records {
		if r.Message == msg {
			out = append(out, r.Level)
		}
	}
	return out
}

func (c *logCapture) count(msg string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, r := range c.records {
		if r.Message == msg {
			n++
		}
	}
	return n
}

// newPoller 는 httptest 서버를 향하는 폴러를 만든다. 폴 주기는 테스트가 정한다 —
// poll 1 회의 상한 컨텍스트가 곧 이 값이라(계획 3절 ⑵) 정지 응답 케이스가 이 값을 쓴다.
func newPoller(t *testing.T, url string, interval time.Duration) (*Poller, *logCapture) {
	t.Helper()
	logs := &logCapture{}
	p, err := NewPoller(Options{
		APIURL:       url,
		PollInterval: interval,
		BootWait:     200 * time.Millisecond,
		Log:          slog.New(logs),
	})
	if err != nil {
		t.Fatalf("NewPoller 실패: %v", err)
	}
	return p, logs
}

// serve 는 요청마다 bodies 를 순서대로 돌려주는 서버를 띄운다.
// 마지막 응답은 그 뒤 요청에도 계속 쓰인다.
func serve(t *testing.T, bodies ...string) *httptest.Server {
	t.Helper()
	var mu sync.Mutex
	n := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		body := bodies[min(n, len(bodies)-1)]
		n++
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	return srv
}

// ---------------------------------------------------------------------------
// ⓑ 단일 페이지 — 원자 교체 ∧ Publishing 3 항 결합 ∧ EpochStartedAt = onlineTime
// ---------------------------------------------------------------------------

func TestSinglePageResponseBecomesSnapshot(t *testing.T) {
	srv := serve(t, rawPublishing)
	p, logs := newPoller(t, srv.URL, time.Second)

	before := time.Now()
	p.pollOnce(context.Background())
	after := time.Now()

	obs := p.Latest(probeStream)
	if !obs.Publishing {
		t.Error("송출 중 응답인데 Publishing 이 거짓이다")
	}
	if !obs.EpochKnown {
		t.Error("onlineTime 이 실재하는데 EpochKnown 이 거짓이다")
	}
	if !obs.EpochStartedAt.Equal(probeOnlineTime) {
		t.Errorf("EpochStartedAt = %v, want onlineTime %v", obs.EpochStartedAt, probeOnlineTime)
	}
	if obs.Tier != TierOnlineTime {
		t.Errorf("Tier = %v, want TierOnlineTime(ⓘ)", obs.Tier)
	}
	// ObservedAt 은 poll **시작** 시각이다(완료 시각이 아니다 — 계획 3절).
	if obs.ObservedAt.Before(before) || obs.ObservedAt.After(after) {
		t.Errorf("ObservedAt = %v, want [%v, %v] 안의 poll 시작 시각", obs.ObservedAt, before, after)
	}
	if n := logs.count("mtxstate_poll_failed"); n != 0 {
		t.Errorf("정상 응답인데 mtxstate_poll_failed 가 %d 건 떴다", n)
	}
}

// ObservedAt 은 완료가 아니라 시작 시각이다. 느린 응답에서만 둘이 갈린다.
func TestObservedAtIsPollStartTime(t *testing.T) {
	const delay = 300 * time.Millisecond
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(delay)
		_, _ = w.Write([]byte(rawPublishing))
	}))
	t.Cleanup(srv.Close)

	p, _ := newPoller(t, srv.URL, 5*time.Second)
	before := time.Now()
	p.pollOnce(context.Background())

	got := p.Latest(probeStream).ObservedAt
	if lag := got.Sub(before); lag >= delay {
		t.Errorf("ObservedAt 이 시작보다 %v 늦다 — 완료 시각을 쓰고 있다(응답 지연 %v)", lag, delay)
	}
}

// Publishing 은 세 항의 결합이다 — 하나라도 빠지면 거짓이어야 한다.
func TestPublishingRequiresOnlineAndSource(t *testing.T) {
	cases := map[string]string{
		"online_false": `{"itemCount":1,"pageCount":1,"items":[{"name":"probe-stream","online":false,"onlineTime":"2026-09-01T16:25:40.78854881Z","source":{"type":"rtmpConn","id":"x"}}]}`,
		"source_null":  `{"itemCount":1,"pageCount":1,"items":[{"name":"probe-stream","online":true,"onlineTime":"2026-09-01T16:25:40.78854881Z","source":null}]}`,
		"source_없음":    `{"itemCount":1,"pageCount":1,"items":[{"name":"probe-stream","online":true,"onlineTime":"2026-09-01T16:25:40.78854881Z"}]}`,
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			srv := serve(t, body)
			p, _ := newPoller(t, srv.URL, time.Second)

			p.pollOnce(context.Background())

			if p.Latest(probeStream).Publishing {
				t.Error("세 항 중 하나가 빠졌는데 Publishing 이 참이다")
			}
		})
	}
}

// ---------------------------------------------------------------------------
// ⓐ 빈 목록 — 부재는 정상 음성이다(관측 실패가 아니다)
// ---------------------------------------------------------------------------

func TestEmptyListIsNormalNegative(t *testing.T) {
	srv := serve(t, rawEmpty)
	p, logs := newPoller(t, srv.URL, time.Second)

	p.pollOnce(context.Background())

	obs := p.Latest(probeStream)
	if obs.Publishing {
		t.Error("빈 목록인데 Publishing 이 참이다")
	}
	// 부재는 Publishing 축으로만 표현한다 — EpochKnown 축이 아니다(계획 3절 r13 정본).
	if !obs.EpochKnown {
		t.Error("폴이 성공했는데 EpochKnown 이 거짓이다 — 부재는 EpochKnown 축이 아니다")
	}
	if obs.ObservedAt.IsZero() {
		t.Error("성공한 폴인데 ObservedAt 이 비었다")
	}
	if n := logs.count("mtxstate_poll_failed"); n != 0 {
		t.Errorf("빈 목록은 정상인데 mtxstate_poll_failed 가 %d 건 떴다", n)
	}
}

// 송출 종료 = 항목 소멸이다. 전이가 Publishing 을 뒤집어야 한다.
func TestItemDisappearanceFlipsPublishing(t *testing.T) {
	srv := serve(t, rawPublishing, rawEmpty)
	p, _ := newPoller(t, srv.URL, time.Second)

	p.pollOnce(context.Background())
	if !p.Latest(probeStream).Publishing {
		t.Fatal("첫 폴에서 Publishing 이 참이어야 한다")
	}
	firstAt := p.Latest(probeStream).ObservedAt

	p.pollOnce(context.Background())

	obs := p.Latest(probeStream)
	if obs.Publishing {
		t.Error("항목이 사라졌는데 Publishing 이 참이다")
	}
	if !obs.ObservedAt.After(firstAt) {
		t.Error("성공한 폴인데 ObservedAt 이 전진하지 않았다")
	}
}

// 항목 실재 ∧ onlineTime 부재 — EpochKnown=false 두 국면 중 하나다.
func TestItemWithoutOnlineTimeIsEpochUnknown(t *testing.T) {
	srv := serve(t, `{"itemCount":1,"pageCount":1,"items":[{"name":"probe-stream","online":true,"source":{"type":"rtmpConn","id":"x"}}]}`)
	p, _ := newPoller(t, srv.URL, time.Second)

	p.pollOnce(context.Background())

	if p.Latest(probeStream).EpochKnown {
		t.Error("onlineTime 이 없는데 EpochKnown 이 참이다 — ⓐ2 가 fail-closed 되지 않는다")
	}
}

// ---------------------------------------------------------------------------
// ⓒ pageCount > 1 — 그 poll 을 통째로 버린다
// ---------------------------------------------------------------------------

func TestMultiPageResponseDiscardsPoll(t *testing.T) {
	multi := `{"itemCount":2,"pageCount":2,"items":[{"name":"other","online":true,"onlineTime":"2026-09-01T16:25:40.78854881Z","source":{"type":"rtmpConn","id":"y"}}]}`
	srv := serve(t, rawPublishing, multi)
	p, logs := newPoller(t, srv.URL, time.Second)

	p.pollOnce(context.Background())
	kept := p.Latest(probeStream)

	p.pollOnce(context.Background())

	obs := p.Latest(probeStream)
	if !obs.Publishing {
		t.Error("여러 페이지 응답은 버려야 한다 — 직전 스냅샷이 살아 있어야 한다")
	}
	if !obs.ObservedAt.Equal(kept.ObservedAt) {
		t.Errorf("ObservedAt 이 갱신됐다: %v → %v (버린 poll 은 시각도 남기지 않는다)",
			kept.ObservedAt, obs.ObservedAt)
	}
	if p.Latest("other").Publishing {
		t.Error("버린 응답의 항목이 스냅샷에 실렸다")
	}
	if n := logs.countReason("mtxstate_poll_failed", "multi_page"); n != 1 {
		t.Errorf("mtxstate_poll_failed{reason=multi_page} = %d 건, want 1", n)
	}
}

// ---------------------------------------------------------------------------
// ⓓ 요청 실패(성공 이력 보유) — 직전 스냅샷 유지
// ---------------------------------------------------------------------------

func TestRequestFailureKeepsPreviousSnapshot(t *testing.T) {
	var mu sync.Mutex
	fail := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		f := fail
		mu.Unlock()
		if f {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		_, _ = w.Write([]byte(rawPublishing))
	}))
	t.Cleanup(srv.Close)

	p, logs := newPoller(t, srv.URL, time.Second)
	p.pollOnce(context.Background())
	kept := p.Latest(probeStream)

	mu.Lock()
	fail = true
	mu.Unlock()
	p.pollOnce(context.Background())

	obs := p.Latest(probeStream)
	if !obs.Publishing || !obs.EpochKnown {
		t.Error("성공 이력이 있는 폴러의 실패는 직전 스냅샷을 유지해야 한다")
	}
	if !obs.ObservedAt.Equal(kept.ObservedAt) {
		t.Errorf("실패한 poll 이 ObservedAt 을 갱신했다: %v → %v", kept.ObservedAt, obs.ObservedAt)
	}
	if n := logs.countReason("mtxstate_poll_failed", "request_failed"); n != 1 {
		t.Errorf("mtxstate_poll_failed{reason=request_failed} = %d 건, want 1", n)
	}
}

// 200 이 아닌 응답도 request_failed 다 — 본문을 파싱해 스냅샷으로 삼지 않는다.
// (인증 실패는 200 이 아니라 4xx 로 오고 본문은 {"error":"authentication error"} 다.)
func TestNon200ResponseIsRequestFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"status":"error","error":"authentication error"}`))
	}))
	t.Cleanup(srv.Close)

	p, logs := newPoller(t, srv.URL, time.Second)
	p.pollOnce(context.Background())

	if p.Latest(probeStream).EpochKnown {
		t.Error("인증 실패인데 관측이 성립했다 — fail-closed 여야 한다")
	}
	if n := logs.countReason("mtxstate_poll_failed", "request_failed"); n != 1 {
		t.Errorf("mtxstate_poll_failed{reason=request_failed} = %d 건, want 1", n)
	}
}

// 깨진 본문(파싱 실패)도 request_failed 로 합류한다 — 사유 라벨은 둘뿐이다.
func TestMalformedBodyIsRequestFailure(t *testing.T) {
	srv := serve(t, `{"itemCount":1,`)
	p, logs := newPoller(t, srv.URL, time.Second)

	p.pollOnce(context.Background())

	if p.Latest(probeStream).EpochKnown {
		t.Error("파싱 실패인데 관측이 성립했다")
	}
	if n := logs.countReason("mtxstate_poll_failed", "request_failed"); n != 1 {
		t.Errorf("mtxstate_poll_failed{reason=request_failed} = %d 건, want 1", n)
	}
}

// s4_signal_grades(M3 몫) — 두 사유 모두 **WARN** 이다.
//
// 등급의 근거는 상태다: 프로세스는 살아 있고, 방향은 비주조(안전)이며, 다음 주기가
// 회복시킨다. 그래서 사람을 부르는 ERROR 도, 묻히는 INFO 도 아니다.
// 위 사유별 케이스들은 건수·라벨만 보므로 등급이 바뀌어도 통과한다 — 그 구멍을 메운다.
func TestPollFailureSignalsAreWarnGrade(t *testing.T) {
	multi := `{"itemCount":2,"pageCount":2,"items":[]}`
	for name, srv := range map[string]*httptest.Server{
		"request_failed": serve(t, `{"itemCount":1,`), // 파싱 실패
		"multi_page":     serve(t, multi),
	} {
		p, logs := newPoller(t, srv.URL, time.Second)
		p.pollOnce(context.Background())

		got := logs.levels("mtxstate_poll_failed")
		if len(got) != 1 {
			t.Fatalf("%s: mtxstate_poll_failed 가 %d건이다(1건 기대)", name, len(got))
		}
		if got[0] != slog.LevelWarn {
			t.Errorf("%s: 등급 = %v, want WARN", name, got[0])
		}
	}
}

// ---------------------------------------------------------------------------
// ⓔ 응답 정지 — PollInterval 상한 안에 종결하고 다음 poll 이 회복한다
// ---------------------------------------------------------------------------

func TestHungResponseEndsWithinPollInterval(t *testing.T) {
	const interval = 200 * time.Millisecond
	var mu sync.Mutex
	hang := true
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		h := hang
		mu.Unlock()
		if h {
			<-r.Context().Done() // 헤더조차 보내지 않는다
			return
		}
		_, _ = w.Write([]byte(rawPublishing))
	}))
	t.Cleanup(srv.Close)

	p, logs := newPoller(t, srv.URL, interval)

	start := time.Now()
	p.pollOnce(context.Background())
	elapsed := time.Since(start)

	// 허용폭은 상한 + 스케줄링 여유뿐이다. 4×interval 처럼 넓게 잡으면 상한 자체가
	// 2 배로 늘어나는 회귀(컨텍스트를 2×PollInterval 로 거는 변경)를 그냥 통과시킨다.
	const slack = 100 * time.Millisecond
	if elapsed > interval+slack {
		t.Fatalf("정지 응답에서 poll 이 %v 걸렸다 — 상한 %v(여유 %v) 안에 끝나야 한다", elapsed, interval, slack)
	}
	// 관측 이력 0 에서의 실패 → EpochKnown=false(정본 문장 ⑴).
	if p.Latest(probeStream).EpochKnown {
		t.Error("관측 이력이 0 인데 EpochKnown 이 참이다")
	}
	if n := logs.countReason("mtxstate_poll_failed", "request_failed"); n != 1 {
		t.Errorf("mtxstate_poll_failed{reason=request_failed} = %d 건, want 1", n)
	}

	mu.Lock()
	hang = false
	mu.Unlock()
	p.pollOnce(context.Background())

	if !p.Latest(probeStream).Publishing {
		t.Error("다음 poll 이 정상 회복하지 못했다")
	}
}

// ---------------------------------------------------------------------------
// 기동 — Start·WaitFirstObservation
// ---------------------------------------------------------------------------

func TestWaitFirstObservationReturnsTrueAfterFirstSuccess(t *testing.T) {
	srv := serve(t, rawPublishing)
	p, _ := newPoller(t, srv.URL, time.Second)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p.Start(ctx)

	if !p.WaitFirstObservation(ctx) {
		t.Fatal("첫 관측이 BootWait 안에 들어와야 한다 — Start 는 즉시 1 회 폴한다")
	}
	if !p.Latest(probeStream).Publishing {
		t.Error("첫 관측이 스냅샷에 실리지 않았다")
	}

	cancel()
	select {
	case <-p.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("ctx 취소 후에도 폴러가 끝나지 않는다")
	}
}

func TestWaitFirstObservationTimesOutWithoutObservation(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	t.Cleanup(srv.Close)

	p, _ := newPoller(t, srv.URL, 50*time.Millisecond)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p.Start(ctx)

	if p.WaitFirstObservation(ctx) {
		t.Fatal("관측이 하나도 없는데 참을 돌려줬다")
	}
	// 타임아웃은 기동을 막지 않는다 — 관측 없이 진행하고 ⓐ2 가 fail-closed 된다.
	if p.Latest(probeStream).EpochKnown {
		t.Error("관측 없이 EpochKnown 이 참이다")
	}
}

// 정상 종료로 끊긴 요청은 고장이 아니다 — 신호를 남기면 내릴 때마다 WARN 이
// 하나씩 쌓여 진짜 폴 실패와 구분이 사라진다(위양성).
func TestCancelDuringRequestEmitsNoFailureSignal(t *testing.T) {
	reached := make(chan struct{})
	var once sync.Once
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		once.Do(func() { close(reached) })
		<-r.Context().Done() // 헤더조차 보내지 않고 취소가 끊을 때까지 붙잡는다
	}))
	t.Cleanup(srv.Close)

	// 폴 상한을 넘게 잡아 이 테스트가 재는 것이 상한 만료가 아닌 **취소**임을 보장한다.
	p, logs := newPoller(t, srv.URL, 5*time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	p.Start(ctx)

	select {
	case <-reached:
	case <-time.After(2 * time.Second):
		t.Fatal("첫 폴 요청이 서버에 도달하지 않았다")
	}
	cancel()

	select {
	case <-p.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("ctx 취소 후에도 폴러가 끝나지 않는다")
	}
	if n := logs.count("mtxstate_poll_failed"); n != 0 {
		t.Errorf("정상 종료인데 mtxstate_poll_failed 가 %d 건 떴다 — 취소는 고장이 아니다", n)
	}
}

// run() 의 티커가 실제로 다음 폴을 돌리는지 본다.
//
// 첫 폴은 Start 가 티커 전에 직접 돌리므로 **첫 관측만 보는 테스트는 티커를 지워도
// 통과한다.** 그렇게 되면 스냅샷이 첫 관측에 영구 고정되고, OBS_FRESH 가 지난 뒤
// ⓐ2 가 아무 신호 없이 영구 fail-closed 된다(무징후 정지).
func TestTickerAdvancesObservationPeriodically(t *testing.T) {
	const interval = 50 * time.Millisecond
	srv := serve(t, rawPublishing)
	p, _ := newPoller(t, srv.URL, interval)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p.Start(ctx)

	if !p.WaitFirstObservation(ctx) {
		t.Fatal("첫 관측이 BootWait 안에 들어오지 않았다")
	}
	first := p.Latest(probeStream).ObservedAt

	deadline := time.Now().Add(2 * time.Second)
	for !p.Latest(probeStream).ObservedAt.After(first) {
		if time.Now().After(deadline) {
			t.Fatalf("주기 폴이 돌지 않는다 — ObservedAt 이 첫 관측(%v)에 고정됐다", first)
		}
		time.Sleep(interval / 5)
	}
}

// ---------------------------------------------------------------------------
// 설정 경계
// ---------------------------------------------------------------------------

func TestNewPollerRejectsEmptyAPIURL(t *testing.T) {
	if _, err := NewPoller(Options{}); err == nil {
		t.Fatal("APIURL 이 비었는데 폴러가 만들어졌다 — 빈 값은 '관측 끔'이라 호출자가 갈라야 한다")
	}
}

func TestNewPollerAppliesDefaults(t *testing.T) {
	p, err := NewPoller(Options{APIURL: "http://media:9997"})
	if err != nil {
		t.Fatalf("NewPoller 실패: %v", err)
	}
	if p.opt.PollInterval != DefaultPollInterval {
		t.Errorf("PollInterval = %v, want %v", p.opt.PollInterval, DefaultPollInterval)
	}
	if p.opt.BootWait != DefaultBootWait {
		t.Errorf("BootWait = %v, want %v", p.opt.BootWait, DefaultBootWait)
	}
}

// 베이스 URL 끝의 슬래시는 NewPoller 가 떼어 낸다.
// 안 떼면 요청 경로가 //v3/paths/list 가 되어 서버가 404 로 튕기고, 남는 것은
// 사유가 request_failed 인 신호뿐이라 인증 실패·오구성과 구분되지 않는다.
func TestTrailingSlashInAPIURLIsNormalized(t *testing.T) {
	var mu sync.Mutex
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		gotPath = r.URL.Path
		mu.Unlock()
		_, _ = w.Write([]byte(rawPublishing))
	}))
	t.Cleanup(srv.Close)

	p, _ := newPoller(t, srv.URL+"/", time.Second)
	p.pollOnce(context.Background())

	mu.Lock()
	got := gotPath
	mu.Unlock()
	if got != pathsListPath {
		t.Errorf("요청 경로 = %q, want %q", got, pathsListPath)
	}
	if !p.Latest(probeStream).Publishing {
		t.Error("슬래시만 붙은 URL 인데 관측이 성립하지 않았다")
	}
}
