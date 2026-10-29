package main

// 계약9 발행기의 기동 조립(POK-196 M6-8 커밋 ④ — 계획 4.18 ④ · 설계 M6 15.5 「발행 켜기 게이트」 · 7절 활성화 선행 조건 ·
// D45 · D47 · D50).

import (
	"context"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/broadcast"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/keyresolve"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

const lifecycleTopic = "arn:aws:sns:ap-northeast-2:123456789012:pokeclip-test-broadcast-lifecycle.fifo"

// lifecycleCfg 는 주제 · 허용 목록만 채운 설정이다.
func lifecycleCfg(topic string, streams ...string) config.Config {
	return config.Config{Lifecycle: config.LifecycleOptions{TopicARN: topic, TopicRegion: "ap-northeast-2", Streams: streams}}
}

// lifecycleResolver 는 조립 게이트만 지나는 4C 클라이언트다 — 이 파일의 시험은 resolve 를 부르지 않는다.
func lifecycleResolver(t *testing.T) *keyresolve.Client {
	t.Helper()
	c, err := keyresolve.NewClient(keyresolve.ClientOptions{BaseURL: "http://127.0.0.1:1", Token: keyresolve.NewSecret("lifecycle-test")})
	if err != nil {
		t.Fatalf("resolve 클라이언트 조립 실패: %v", err)
	}
	return c
}

// 주제가 비면(롤백 스위치) 허용 목록이 비면 꺼짐이다 — INFO 한 줄만 내고 DB 를 보지 않으며 게이트는 영값이다(D45).
func TestStartLifecycleOffWithoutTopicOrStreams(t *testing.T) {
	for _, c := range []struct {
		cfg    config.Config
		reason string
	}{
		{lifecycleCfg("", "*"), "no_topic"},
		{lifecycleCfg(lifecycleTopic), "no_streams"},
	} {
		log, buf := expiryLog()
		gate, stop := startLifecycle(t.Context(), c.cfg, nil, nil, log)
		stop()
		lines := logLines(t, buf, "lifecycle_disabled")
		if gate.Enabled() || len(lines) != 1 || lines[0]["reason"] != c.reason || lines[0]["level"] != "INFO" {
			t.Errorf("%s: 게이트 켬 %v · lifecycle_disabled %v · 전체 %s", c.reason, gate.Enabled(), lines, buf.String())
		}
	}
}

// 주제 · 허용 목록 · 권한이 다 있어도 4C resolve 설정이 없으면 꺼짐이다 — 켜면 새 회차 started 가 모두 resolve_unwired 로
// 버려지고 같은 회차 ended 도 막힌다(보안 A3-2). ERROR lifecycle_disabled reason=no_resolver 한 줄이고 게이트는 영값이다.
// 변이 = resolver 확인 제거 → 게이트가 켜져 FAIL.
func TestStartLifecycleOffWithoutResolver(t *testing.T) {
	role := pgtest.NewMediaIndexerRole(t, newWireTestPool(t))
	assemblyCredentialsEnv(t)
	log, buf := expiryLog()

	gate, stop := startLifecycle(t.Context(), lifecycleCfg(lifecycleTopic, "*"), role.Pool, nil, log)
	stop()

	lines := logLines(t, buf, "lifecycle_disabled")
	if gate.Enabled() || len(lines) != 1 || lines[0]["reason"] != "no_resolver" || lines[0]["level"] != "ERROR" {
		t.Fatalf("게이트 켬 %v · lifecycle_disabled %v · 전체 %s", gate.Enabled(), lines, buf.String())
	}
	if started := logLines(t, buf, "lifecycle_started"); len(started) != 0 {
		t.Fatalf("lifecycle_started %v, want 없음", started)
	}
}

// D50 — outbox INSERT 권한이 없는 계정이면 ERROR lifecycle_disabled reason=privilege 이고 게이트는 꺼진다. 그 게이트로
// 조립한 결정자의 개시는 outbox 문장을 보내지 않아 42501 없이 통과한다. 변이 = 권한 확인 제거 → 개시가 42501 로 FAIL.
func TestStartLifecycleOffWhenOutboxPrivilegeMissingD50(t *testing.T) {
	role := pgtest.NewMediaIndexerRole(t, newWireTestPool(t))
	role.Revoke(t, "INSERT", "broadcast_lifecycle_outbox")
	t.Cleanup(func() { role.RequireNoneDenied(t) })
	log, buf := expiryLog()

	gate, stop := startLifecycle(t.Context(), lifecycleCfg(lifecycleTopic, "*"), role.Pool, lifecycleResolver(t), log)
	defer stop()

	lines := logLines(t, buf, "lifecycle_disabled")
	if gate.Enabled() || len(lines) != 1 || lines[0]["reason"] != "privilege" || lines[0]["level"] != "ERROR" ||
		!strings.Contains(lines[0]["missing"].(string), "broadcast_lifecycle_outbox:INSERT") {
		t.Fatalf("게이트 켬 %v · lifecycle_disabled %v", gate.Enabled(), lines)
	}
	d := newSessionDecider(time.Second, 30*time.Second, 300*time.Second, gate, log)
	if id := openVia(t, role.Pool, d, "d50", 1, wireBase, 4000); id == "" {
		t.Fatal("개시가 서지 않았다")
	}
}

// 켜짐 — 주제 · 허용 목록 · resolver · 권한이 다 있으면 게이트가 켜지고 발행기가 뜬다(INFO lifecycle_started). 정지 함수가
// 돌아온다.
func TestStartLifecycleStartsPublisher(t *testing.T) {
	role := pgtest.NewMediaIndexerRole(t, newWireTestPool(t))
	assemblyCredentialsEnv(t)
	log, buf := expiryLog()

	gate, stop := startLifecycle(t.Context(), lifecycleCfg(lifecycleTopic, "*"), role.Pool, lifecycleResolver(t), log)
	stop()

	started := logLines(t, buf, "lifecycle_started")
	if !gate.Enabled() || len(started) != 1 || started[0]["topic"] != lifecycleTopic {
		t.Fatalf("게이트 켬 %v · lifecycle_started %v · 전체 %s", gate.Enabled(), started, buf.String())
	}
}

// D47 — 발행 중 정지. 정지 함수는 발행기 Run 이 돌아올 때까지 기다리고, 끊긴 행은 pending 그대로다(다음 기동이 같은
// eventId 로 다시 낸다). 변이 = 정지가 기다리지 않음 → Run 이 끝나기 전에 돌아와 FAIL.
func TestLifecycleStopWaitsForPublisherD47(t *testing.T) {
	pool := newWireTestPool(t)
	if _, err := pool.Exec(context.Background(), `
INSERT INTO broadcast_lifecycle_outbox (event_id, round_id, event_type, sequence, occurred_at, ingest_stream_id, streamer_id)
VALUES ('S-1.started', 'S-1', $1, 1, $2, 'd47', '42')`, broadcast.EventStarted, wireBase); err != nil {
		t.Fatalf("outbox 픽스처 실패: %v", err)
	}
	entered := make(chan struct{})
	var returned atomic.Bool
	publish := func(ctx context.Context, _ broadcast.Message) error {
		close(entered)
		<-ctx.Done()
		time.Sleep(200 * time.Millisecond) // 끊긴 발행이 늦게 돌아온다
		returned.Store(true)
		return ctx.Err()
	}
	log, _ := expiryLog()
	stop, err := runLifecyclePublisher(t.Context(), pool, broadcast.PublisherOptions{Publish: publish, Log: log})
	if err != nil {
		t.Fatalf("발행기 기동 실패: %v", err)
	}
	select {
	case <-entered:
	case <-time.After(10 * time.Second):
		t.Fatal("발행이 시작되지 않았다")
	}
	stop()
	if !returned.Load() {
		t.Fatal("정지 함수가 발행기 귀환 전에 돌아왔다 — 풀 Close 가 진행 중 문장을 만난다")
	}
	var state string
	if err := pool.QueryRow(context.Background(), `SELECT state FROM broadcast_lifecycle_outbox WHERE event_id='S-1.started'`).
		Scan(&state); err != nil || state != "pending" {
		t.Fatalf("끊긴 행 state = (%q, %v), want pending", state, err)
	}
}

// run 은 발행 게이트를 풀 확인 뒤 · 결정자 조립 전에 한 번 만들고, 그 게이트를 결정자 · 정산 조립 · 4D 칸에 넘긴다. 정지
// defer 는 pool.Close 뒤에 등록돼 먼저 돈다(D47). run() 은 DB · 워처가 있어야 돌아 소스에서 본다.
func TestRunWiresLifecycleGate(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)
	const call = "lifecycle, stopLifecycle := startLifecycle(ctx, cfg, pool, resolver, log)"
	if n := strings.Count(text, "startLifecycle("); n != 1 {
		t.Fatalf("startLifecycle 호출 %d번, want 한 번", n)
	}
	if strings.Contains(text, "broadcast.Gate{}") {
		t.Error("main.go 에 영값 게이트가 남았다 — 설정 게이트를 넘겨야 한다")
	}
	order := []string{
		"defer pool.Close()",
		call,
		"defer stopLifecycle()",
		"newSessionDecider(cfg.SessionFloorSlack, cfg.ObsFresh, cfg.ReconnectWindow, lifecycle, log)",
		"fa := newFinalizeAssembly(cfg, lifecycle, log)",
		"newIntentOptions(log, fa.closeRound(), startedEnqueuer(lifecycle))",
	}
	prev := -1
	for _, want := range order {
		at := strings.Index(text, want)
		if at < 0 {
			t.Fatalf("%q 를 찾지 못했다", want)
		}
		if at <= prev {
			t.Fatalf("%q 의 자리가 앞선다 — 차례는 %v 여야 한다", want, order)
		}
		prev = at
	}
}
