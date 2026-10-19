package main

// 관측 폴러의 Control API 자격 배선(POK-373) 시험 — env → config.Load → observerOptions(파일 읽기) →
// startObserver → 요청의 Basic 자격. run() 은 DB · 워처가 있어야 돌아 단위 시험이 불가능하므로(boot_test.go
// 의 TestBootOrderPutsObserverBeforeInitialCollect 와 같은 사정) 호출 위치는 소스 위치로 고정한다.

import (
	"context"
	"encoding/base64"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/config"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/mtxstate"
)

// 시험 자격은 합성이다. 실패 문구는 비밀번호를 찍지 않는다 — 맞았는지 · 실렸는지만 본다.
const (
	bootAuthUser = "sidecar"
	bootAuthPass = "boot:canary pass-0001"
)

// text 는 담긴 기록 전부를 메시지 · 속성 값까지 한 문자열로 편다(누설 시험이 「어디에도 없음」 을 보게).
func (c *bootLogCapture) text() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	var b strings.Builder
	for _, r := range c.records {
		b.WriteString(r.Message)
		r.Attrs(func(a slog.Attr) bool {
			fmt.Fprintf(&b, " %s=%v", a.Key, a.Value.Any())
			return true
		})
		b.WriteString("\n")
	}
	return b.String()
}

// passLeaked 는 s 에 비밀번호(원문 · Basic 헤더 값)가 실렸는지 본다.
func passLeaked(s string) bool {
	basic := base64.StdEncoding.EncodeToString([]byte(bootAuthUser + ":" + bootAuthPass))
	return strings.Contains(s, bootAuthPass) || strings.Contains(s, basic)
}

// mtxstate_started 는 자격 방식(auth)과 사용자 이름(user)을 싣는다 — 운영자가 「자격을 싣고 붙었나」 를 기동
// 한 줄로 확인한다. 비밀번호 · 파일 경로는 싣지 않는다.
func TestStartObserverLogsAuthScheme(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(bootRawPublishing))
	}))
	defer srv.Close()

	for _, c := range []struct {
		name               string
		opt                mtxstate.Options
		wantAuth, wantUser string
	}{
		{name: "자격 있음", opt: mtxstate.Options{User: bootAuthUser, Pass: mtxstate.NewSecret(bootAuthPass)}, wantAuth: "basic", wantUser: bootAuthUser},
		{name: "자격 없음", wantAuth: "none", wantUser: ""},
	} {
		c.opt.APIURL, c.opt.PollInterval, c.opt.BootWait = srv.URL, time.Second, 3*time.Second
		logs := &bootLogCapture{}
		observer, stop := startObserver(context.Background(), c.opt, slog.New(logs))
		stop()

		if observer == nil {
			t.Errorf("%s: 관측자가 nil 이다", c.name)
		}
		if got, ok := logs.attr("mtxstate_started", "auth"); !ok || got != c.wantAuth {
			t.Errorf("%s: mtxstate_started 의 auth = %v(있음=%v), want %q", c.name, got, ok, c.wantAuth)
		}
		if got, ok := logs.attr("mtxstate_started", "user"); !ok || got != c.wantUser {
			t.Errorf("%s: mtxstate_started 의 user = %v(있음=%v), want %q", c.name, got, ok, c.wantUser)
		}
		if passLeaked(logs.text()) {
			t.Errorf("%s: 로그에 카나리아 발견", c.name)
		}
	}
}

// 설정한 비밀번호가 실제 요청의 Basic 자격까지 간다 — env 를 읽는 config 부터 파일을 읽는 main, 자격을
// 싣는 폴러까지 한 줄로 잇는다. 어느 로그에도 비밀번호는 없다.
func TestConfiguredPasswordReachesControlAPI(t *testing.T) {
	var mu sync.Mutex
	var total, matched int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		mu.Lock()
		total++
		if ok && user == bootAuthUser && pass == bootAuthPass {
			matched++
		}
		mu.Unlock()
		_, _ = w.Write([]byte(bootRawPublishing))
	}))
	defer srv.Close()

	passFile := writeSecret(t, t.TempDir(), "mtx_api_pass", bootAuthPass+"\n", 0o400)
	env := map[string]string{
		"POSTGRES_USER":     "pokeclip",
		"POSTGRES_PASSWORD": "합성-예시",
		"POSTGRES_DB":       "pokeclip",
		"MTX_API_URL":       srv.URL, // httptest 는 127.0.0.1 — 자격과 함께 쓸 수 있는 IP 리터럴이다
		"MTX_API_USER":      bootAuthUser,
		"MTX_API_PASS_FILE": passFile,
	}
	cfg, err := config.Load(func(k string) string { return env[k] })
	if err != nil {
		t.Fatalf("config.Load 에러: %v", err)
	}
	opt, err := observerOptions(cfg)
	if err != nil {
		t.Fatalf("observerOptions 에러: %v", err)
	}

	logs := &bootLogCapture{}
	observer, stop := startObserver(context.Background(), opt, slog.New(logs))
	defer stop()

	if observer == nil {
		t.Fatal("관측자가 nil 이다")
	}
	if got, _ := logs.attr("mtxstate_started", "first_observation"); got != true {
		t.Errorf("mtxstate_started 의 first_observation = %v, want true", got)
	}
	mu.Lock()
	gotTotal, gotMatched := total, matched
	mu.Unlock()
	if gotTotal == 0 || gotMatched != gotTotal {
		t.Errorf("요청 %d 회 중 자격이 맞은 것 %d 회, want 전부", gotTotal, gotMatched)
	}
	if passLeaked(logs.text()) {
		t.Error("로그에 카나리아 발견")
	}
	if passLeaked(fmt.Sprintf("%+v %#v", cfg, opt)) {
		t.Error("Config · Options 를 찍은 값에 카나리아 발견")
	}
}

// 비밀번호 파일을 못 읽으면 observerOptions 가 에러를 낸다 — run 은 그 에러로 기동 실패한다(ADR-063 「관측
// 실패는 강등」 의 예외, kty 2026-10-19). 강등으로 뜨면 운영자가 끄지도 않은 관측이 조용히 사라진다.
// 비밀번호를 경로 자리에 잘못 넣은 경우('/' 로 시작해 절대 경로 검사를 지난다)에도 에러에 값이 없다.
func TestObserverOptionsFailsOnBadPassFile(t *testing.T) {
	dir := t.TempDir()
	for name, path := range map[string]string{
		"없는 파일":       filepath.Join(dir, "missing"),
		"other 비트":    writeSecret(t, dir, "world", bootAuthPass+"\n", 0o644),
		"경로 자리의 비밀번호": "/" + bootAuthPass,
	} {
		cfg := config.Config{
			MTXState:       mtxstate.Options{APIURL: "http://127.0.0.1:9997", User: bootAuthUser},
			MTXAPIPassFile: path,
		}
		_, err := observerOptions(cfg)
		if err == nil {
			t.Errorf("%s: observerOptions 가 에러 없이 돌아왔다 — 기동 실패여야 한다", name)
			continue
		}
		if !strings.Contains(err.Error(), "MTX_API_PASS_FILE") || passLeaked(err.Error()) {
			t.Errorf("%s: 에러에 env 이름이 없거나 카나리아 발견", name)
		}
	}
}

// MTX_API_URL 이 비면(관측 끔 — 즉시 롤백 스위치) 비밀번호 파일을 읽지 않는다. 롤백이 파일 문제에 막히면
// 안 된다(S3_BUCKET 「꺼진 부품 설정이 기동을 죽이지 않는다」 와 같은 규칙).
func TestObserverOptionsSkipsPassFileWhenObservationDisabled(t *testing.T) {
	cfg := config.Config{
		MTXState:       mtxstate.Options{User: bootAuthUser},
		MTXAPIPassFile: filepath.Join(t.TempDir(), "missing"),
	}
	opt, err := observerOptions(cfg)
	if err != nil {
		t.Fatalf("관측이 꺼졌는데 observerOptions 가 파일을 읽어 실패했다: %v", err)
	}
	if opt.APIURL != "" {
		t.Errorf("APIURL = %q, want \"\"(관측 끔이 그대로 가야 한다)", opt.APIURL)
	}
}

// 비밀번호 파일은 DB 연결보다 **먼저** 읽는다 — 설정 오류가 DB 상태와 무관하게 먼저 드러난다(DB 가
// 죽은 상자에서 「DB 실패」 가 나면 파일은 통과했다는 판별 신호가 된다). 정의 · 주석과 겹치지 않는 호출 꼴로
// 찾는다.
func TestBootOrderReadsPassFileBeforeDB(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)
	const call = "obsOpt, err := observerOptions(cfg)"
	const db = "pgxpool.New("
	callAt, dbAt := strings.Index(text, call), strings.Index(text, db)
	if callAt < 0 || dbAt < 0 {
		t.Fatalf("%q 위치 %d · %q 위치 %d — 기동 단계가 사라졌거나 이름이 바뀌었다", call, callAt, db, dbAt)
	}
	if callAt > dbAt {
		t.Errorf("%q 가 %q 보다 뒤에 있다 — 비밀번호 파일은 DB 연결 전에 읽어야 한다", call, db)
	}
	if !strings.Contains(text, "startObserver(ctx, obsOpt, log)") {
		t.Error("startObserver 가 observerOptions 의 결과를 받지 않는다 — 읽은 비밀번호가 폴러에 가지 않는다")
	}
}
