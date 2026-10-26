package main

// 60일 만료의 run 배선(POK-196 M6-4 c5 · 계획 4.14 · 설계 M6 5절 기동 차례).

import (
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/session"
)

// run 은 만료를 받은편지함의 Changed 로 한 번 띄운다. 자리는 seed · 받은편지함 · 발행 몫 판정 뒤, 제어면 서버 앞이고
// 정지 defer 는 pool.Close 보다 뒤에 등록돼 먼저 돈다(D24). run() 은 DB · 워처가 있어야 돌아 단위 시험이 불가능하므로
// (TestShutdownDeferRegistrationOrder) 소스에서 본다 — nil 배선 · 호출 누락 · 자리 이동이 모두 여기서 걸린다.
func TestRunWiresExpiryToPlaybackInbox(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)
	const call = "stopExpiry := startExpiry(ctx, cfg, pool, pubPool != nil, inbox.Changed, log)"
	if n := strings.Count(text, "startExpiry("); n != 1 || !strings.Contains(text, call) {
		t.Fatalf("main.go 의 startExpiry 호출 %d번, want %q 한 번 — 받은편지함이 아닌 표지(nil 등)로 배선했거나 호출이 빠졌다", n, call)
	}
	if strings.Contains(text, "_ = inbox") {
		t.Error("`_ = inbox` 가 남았다 — 읽는 쪽이 붙었으니 지운다")
	}
	order := []string{
		"defer pool.Close()",
		"board := assembleStatus(",
		"inbox := session.NewPlaybackInbox()",
		"pubPool := assemblePublishing(",
		call,
		"defer stopExpiry()",
		"startControlWithEndSignal(",
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

// 주소(CLIP_INTERNAL_URL)가 비면 만료는 꺼진다 — 사유 한 줄(INFO · no_url)만 내고 DB · 창구를 보지 않는다(롤백 스위치).
// 설정은 기동과 같은 config.Load 로 읽는다.
func TestStartExpiryOffWhenClipURLEmpty(t *testing.T) {
	env := map[string]string{"POSTGRES_USER": "u", "POSTGRES_PASSWORD": "p", "POSTGRES_DB": "d", "S3_BUCKET": ""}
	cfg, err := config.Load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatalf("config.Load: %v", err)
	}
	log, buf := expiryLog()
	stop := startExpiry(t.Context(), cfg, nil, true, session.NewPlaybackInbox().Changed, log)
	stop()
	lines := logLines(t, buf, "expiry_disabled")
	if len(lines) != 1 || lines[0]["reason"] != "no_url" || lines[0]["level"] != "INFO" {
		t.Fatalf("expiry_disabled %v · 전체 %s", lines, buf.String())
	}
	if len(logLines(t, buf, "expiry_started")) != 0 {
		t.Fatal("주소 없이 루프를 띄웠다")
	}
}

// 주소를 채우면 루프가 돌고, 장부를 바꾼 방송의 상태 표지가 run 이 만든 것과 같은 받은편지함(session.PlaybackInbox)에
// 닿는다 — M6-3b 의 Drain 이 그것을 읽는다(계획 4.14 「nil 배선 금지」).
func TestStartExpiryDeliversMarkerToPlaybackInbox(t *testing.T) {
	admin := newWireTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	assemblyCredentialsEnv(t)
	s3 := newFakeS3(t, http.StatusOK)
	clip := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"broadcasts":[{"streamId":"b-1","ingestStreamId":"exp-s1",` +
			`"segmentsFrom":"2026-01-01T00:00:00Z","segmentsUntil":"2026-01-02T00:00:00Z"}],` +
			`"nextCursor":null,"hasMore":false}`))
	}))
	t.Cleanup(clip.Close)
	cfg := assemblyCfg(t, s3.srv.URL)
	cfg.Expiry.ClipURL = clip.URL
	inbox := session.NewPlaybackInbox()
	log, buf := expiryLog()

	stop := startExpiry(t.Context(), cfg, role.Pool, true, inbox.Changed, log)
	var got []string
	deadline := time.Now().Add(10 * time.Second)
	for len(got) == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
		got = inbox.Drain()
	}
	stop()
	if !slices.Contains(got, "exp-s1") {
		t.Fatalf("받은편지함 = %v, want exp-s1 포함 · 로그 %s", got, buf.String())
	}
	if len(logLines(t, buf, "expiry_started")) != 1 {
		t.Fatalf("루프 기동 줄이 없다: %s", buf.String())
	}
}
