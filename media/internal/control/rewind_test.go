package control

// 스트림 경로 GET /api/streams/{streamId}/rewind 의 시험이다(설계 M5 3.1 · 3.2 · 3.4 · 3.5 · 3.6 · 8절).
// 응답은 늘 키 다섯을 싣고(값이 없으면 null), 머리는 상태 코드와 무관하게 한 함수가 쓴다(R11). 503 은 Board
// (첫 스냅숏 전 · 부팅 전 모름 · 낡음)와 한도에서만 온다.

import (
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind/status"
)

const rewindPath = "/api/streams/%s/rewind"

func streamURL(base, s string) string { return base + strings.Replace(rewindPath, "%s", s, 1) }

func TestRewindRouteBodies(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{
		"vod":  vodEntry("R1"),
		"live": {Stream: status.Status{Mode: status.ModeLive}},
		"null": {},
	})
	base := f.start(t, RewindRoute(f.holder, f.opt))
	for _, c := range []struct {
		stream, want string
		code         int
	}{
		{"vod", `{"ready":true,"mode":"vod","url":"https://m.example/vod/s/R1/index.m3u8","firstPdt":"2026-10-25T11:00:00.000Z","headPdt":"2026-10-25T11:59:58.500Z"}`, 200},
		{"live", `{"ready":false,"mode":"live","url":null,"firstPdt":null,"headPdt":null}`, 200},
		{"null", `{"ready":false,"mode":null,"url":null,"firstPdt":null,"headPdt":null}`, 200},
		{"none", `{"error":"not_found"}`, 404},
	} {
		got := get(t, streamURL(base, c.stream))
		if got.code != c.code || strings.TrimSpace(got.body) != c.want {
			t.Errorf("%s: (%d, %s), want (%d, %s)", c.stream, got.code, got.body, c.code, c.want)
		}
	}
}

// R11: 머리는 상태 코드마다 같은 함수가 쓴다 — 200 · 404 는 1초 캐시 · JSON, 503 은 거기에 Retry-After: 1.
func TestRewindRouteHeadersPerStatus(t *testing.T) {
	f := newFixture(10)
	base := f.start(t, RewindRoute(f.holder, f.opt))
	check := func(name string, r reply, code int, retry string) {
		t.Helper()
		if r.code != code || r.header.Get("Cache-Control") != "public, max-age=1" ||
			r.header.Get("Content-Type") != "application/json" || r.header.Get("Retry-After") != retry {
			t.Errorf("%s: code %d Cache-Control %q Content-Type %q Retry-After %q, want %d · public, max-age=1 · application/json · %q",
				name, r.code, r.header.Get("Cache-Control"), r.header.Get("Content-Type"), r.header.Get("Retry-After"), code, retry)
		}
	}
	check("첫 스냅숏 전", get(t, streamURL(base, "vod")), 503, "1")
	f.store(true, map[string]status.Entry{"vod": vodEntry("R1")})
	check("200", get(t, streamURL(base, "vod")), 200, "")
	check("404", get(t, streamURL(base, "none")), 404, "")
}

// 3.1절: streamId 는 발행 키 성분 규칙(^[A-Za-z0-9._-]{1,128}$ · . · .. 제외)이다. 어기면 400 이 아니라 404 다.
func TestRewindRouteRejectsBadStreamIDAsNotFound(t *testing.T) {
	f := newFixture(10)
	long := strings.Repeat("a", 129)
	f.store(true, map[string]status.Entry{".": vodEntry("R1"), "..": vodEntry("R1"), long: vodEntry("R1"), "a b": vodEntry("R1")})
	base := f.start(t, RewindRoute(f.holder, f.opt))
	// . · .. 는 ServeMux 가 경로를 정리해 먼저 404 로 끝낸다 — 검사 함수 자체는 TestValidID 가 본다.
	for _, s := range []string{".", ".."} {
		if got := get(t, streamURL(base, s)); got.code != 404 {
			t.Errorf("%q: %d, want 404", s, got.code)
		}
	}
	for _, s := range []string{long, "a%20b", "a%2Fb"} {
		if got := get(t, streamURL(base, s)); got.code != 404 || strings.TrimSpace(got.body) != `{"error":"not_found"}` {
			t.Errorf("%q: (%d, %s), want 404 not_found", s, got.code, got.body)
		}
	}
	if got := get(t, streamURL(base, strings.Repeat("a", 128))); got.code != 404 {
		t.Errorf("128자: %d, want 404(Board 에 없음 — 검사는 통과)", got.code)
	}
}

// 보안 c3 M1: 퍼센트 인코딩한 경로(%61bc)는 풀면 규칙을 지키는 id 라도 404 다 — 엣지 거절 규칙을 비켜 같은 스트림의
// 칸을 깎지 못하게. 칸도 열지 않는다.
func TestRewindRoutePercentEncodedPathIsNotFound(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"abc": vodEntry("R1")})
	base := f.start(t, RewindRoute(f.holder, f.opt))
	if got := get(t, streamURL(base, "%61bc")); got.code != 404 || strings.TrimSpace(got.body) != `{"error":"not_found"}` {
		t.Errorf("%%61bc = (%d, %s), want 404 not_found", got.code, got.body)
	}
	if n := cellCount(f.opt.Limiter); n != 0 {
		t.Errorf("인코딩 경로 뒤 계수기 칸 = %d, want 0", n)
	}
	if got := get(t, streamURL(base, "abc")); got.code != 200 {
		t.Errorf("abc = %d, want 200", got.code)
	}
}

// HEAD 는 GET 패턴이 받고, 그 밖 메서드는 405 다.
func TestRewindRouteMethods(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"vod": vodEntry("R1")})
	base := f.start(t, RewindRoute(f.holder, f.opt))
	head, err := testClient.Head(streamURL(base, "vod"))
	if err != nil {
		t.Fatal(err)
	}
	head.Body.Close()
	if head.StatusCode != 200 {
		t.Errorf("HEAD = %d, want 200", head.StatusCode)
	}
	post, err := testClient.Post(streamURL(base, "vod"), "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	post.Body.Close()
	if post.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("POST = %d, want 405", post.StatusCode)
	}
}

// 3.5절: Origin 이 CONTROL_CORS_ORIGIN 과 정확히 같을 때만 허용 머리를 싣는다. credentials 머리는 싣지 않는다.
// Vary: Origin 은 설정이 있으면 Origin 과 무관하게 늘 싣는다 — 같은 URL 의 답이 Origin 에 따라 갈리므로, 허용 머리
// 없는 답이 캐시돼 허용 Origin 에 재사용되지 않게(보안 L1).
func TestRewindRouteCORSExactMatchOnly(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"vod": vodEntry("R1")})
	base := f.start(t, RewindRoute(f.holder, f.opt))
	ok := get(t, streamURL(base, "vod"), "Origin", "http://localhost:3000")
	if ok.header.Get("Access-Control-Allow-Origin") != "http://localhost:3000" || ok.header.Get("Vary") != "Origin" ||
		ok.header.Get("Access-Control-Allow-Credentials") != "" {
		t.Errorf("같은 Origin: ACAO %q Vary %q ACAC %q", ok.header.Get("Access-Control-Allow-Origin"), ok.header.Get("Vary"),
			ok.header.Get("Access-Control-Allow-Credentials"))
	}
	for _, o := range []string{"http://evil.example", "http://localhost:3000.evil", ""} {
		r := get(t, streamURL(base, "vod"), "Origin", o)
		if r.header.Get("Access-Control-Allow-Origin") != "" || r.header.Get("Vary") != "Origin" {
			t.Errorf("Origin %q: ACAO %q Vary %q, want 없음 · Origin", o, r.header.Get("Access-Control-Allow-Origin"), r.header.Get("Vary"))
		}
	}
	if r := get(t, streamURL(base, "none"), "Origin", "http://evil.example"); r.code != 404 || r.header.Get("Vary") != "Origin" {
		t.Errorf("404 + 다른 Origin: (%d, Vary %q), want (404, Origin)", r.code, r.header.Get("Vary"))
	}
	f.opt.AllowOrigin = ""
	base = f.start(t, RewindRoute(f.holder, f.opt))
	if r := get(t, streamURL(base, "vod"), "Origin", ""); r.header.Get("Access-Control-Allow-Origin") != "" || r.header.Get("Vary") != "" {
		t.Errorf("설정 빈 값 + 빈 Origin: ACAO %q Vary %q, want 없음 · 없음", r.header.Get("Access-Control-Allow-Origin"), r.header.Get("Vary"))
	}
}

// R3: 낡음 기준은 30초 상수다 — 25초 낡은 스냅숏은 200 을 그대로 내고 31초면 503 이다. 낡음 게이지는 바꾼 쪽이
// 세움 · 내림 한 줄씩 쓴다.
func TestRewindRouteStaleAfterThirtySeconds(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"vod": vodEntry("R1")})
	base := f.start(t, RewindRoute(f.holder, f.opt))
	f.clock.Advance(25 * time.Second)
	if got := get(t, streamURL(base, "vod")); got.code != 200 {
		t.Errorf("25초 낡음 = %d, want 200", got.code)
	}
	f.clock.Advance(6 * time.Second)
	for range 3 {
		if got := get(t, streamURL(base, "vod")); got.code != 503 {
			t.Errorf("31초 낡음 = %d, want 503", got.code)
		}
	}
	f.store(true, map[string]status.Entry{"vod": vodEntry("R1")})
	get(t, streamURL(base, "vod"))
	get(t, streamURL(base, "vod"))
	if set, clear := f.logs.countValue("control_status_stale", 1), f.logs.countWarnValue("control_status_stale", 0); set != 1 || clear != 1 {
		t.Errorf("control_status_stale 세움 %d · WARN 내림 %d, want 1 · 1", set, clear)
	}
}

// 3.4절: 스트림 경로는 Found 답에만 칸을 연다 — 모르는 ID 폭주는 칸을 만들지 않는다(R6). 아는 스트림은 초당
// rps 를 넘으면 503 이다.
func TestRewindRouteLimitsOnlyFoundStreams(t *testing.T) {
	f := newFixture(10)
	f.store(true, map[string]status.Entry{"vod": vodEntry("R1")})
	base := f.start(t, RewindRoute(f.holder, f.opt))
	for i := range 10000 {
		if got := get(t, streamURL(base, "u"+strconv.Itoa(i))); got.code != 404 {
			t.Fatalf("모르는 ID = %d, want 404", got.code)
		}
	}
	if n := cellCount(f.opt.Limiter); n != 0 {
		t.Errorf("모르는 ID 1만 건 뒤 계수기 칸 = %d, want 0", n)
	}
	for i := range 11 {
		got := get(t, streamURL(base, "vod"))
		if want := map[bool]int{true: 200, false: 503}[i < 10]; got.code != want {
			t.Errorf("%d번째 = %d, want %d", i+1, got.code, want)
		}
	}
}

func TestValidID(t *testing.T) {
	for _, c := range []struct {
		id   string
		want bool
	}{
		{"abcXYZ09._-", true}, {strings.Repeat("a", 128), true},
		{"", false}, {".", false}, {"..", false}, {strings.Repeat("a", 129), false},
		{"a b", false}, {"a/b", false}, {"한", false}, {"a\x00", false},
	} {
		if got := validID(c.id); got != c.want {
			t.Errorf("validID(%q) = %v, want %v", c.id, got, c.want)
		}
	}
}
