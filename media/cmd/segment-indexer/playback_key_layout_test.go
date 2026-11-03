package main

// 기동 신호 playback_key_layout 의 검증(POK-525 Ⓑ2-5 · 설계 r9.4 2-2 (다) · 8-1 · 계획 r2 E9 기동 몫 · E24 · E29).
//
// 신호 줄은 운영 스크립트(sidecar-round-keys.sh)와 런북(media-swap.md)이 사이드카 저널에서 세는 계약이다 — 이름 · 키 ·
// 키 순서 · 값을 바이트로 잰다.

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// newLayoutLog 는 시각을 뺀 JSON 로거다 — 줄 전체를 바이트로 비교하려고 시각만 지운다(나머지는 기동 로거와 같은 꼴).
func newLayoutLog(buf *bytes.Buffer) *slog.Logger {
	return slog.New(slog.NewJSONHandler(buf, &slog.HandlerOptions{
		ReplaceAttr: func(groups []string, a slog.Attr) slog.Attr {
			if len(groups) == 0 && a.Key == slog.TimeKey {
				return slog.Attr{}
			}
			return a
		},
	}))
}

// 스위치(legacy · round) × 트리거(present · absent · unknown)의 여섯 칸. 켬인데 트리거가 있다고 확인되지 않으면 WARN 이고
// (켬 재생성 직후 · 운영 SQL 2 적용 전에 한 줄이 예상 — 설계 9-3 7), 그 밖은 INFO 다. unknown 은 조회 오류라 원인을 err 로
// 싣는다. 변이: WARN 조건 제거 → 켬_absent · 켬_unknown FAIL.
func TestLogPlaybackKeyLayoutLineByLayoutAndTrigger(t *testing.T) {
	lookupErr := errors.New("회차 불변 트리거 확인 조회 실패: boom")
	for _, c := range []struct {
		name      string
		roundKeys bool
		present   bool
		err       error
		want      string
	}{
		{name: "끔_present", roundKeys: false, present: true,
			want: `{"level":"INFO","msg":"playback_key_layout","layout":"legacy","round_frozen":"present"}`},
		{name: "끔_absent", roundKeys: false, present: false,
			want: `{"level":"INFO","msg":"playback_key_layout","layout":"legacy","round_frozen":"absent"}`},
		{name: "끔_unknown", roundKeys: false, err: lookupErr,
			want: `{"level":"INFO","msg":"playback_key_layout","layout":"legacy","round_frozen":"unknown","err":"회차 불변 트리거 확인 조회 실패: boom"}`},
		{name: "켬_present", roundKeys: true, present: true,
			want: `{"level":"INFO","msg":"playback_key_layout","layout":"round","round_frozen":"present"}`},
		{name: "켬_absent", roundKeys: true, present: false,
			want: `{"level":"WARN","msg":"playback_key_layout","layout":"round","round_frozen":"absent"}`},
		{name: "켬_unknown", roundKeys: true, err: lookupErr,
			want: `{"level":"WARN","msg":"playback_key_layout","layout":"round","round_frozen":"unknown","err":"회차 불변 트리거 확인 조회 실패: boom"}`},
	} {
		t.Run(c.name, func(t *testing.T) {
			var buf bytes.Buffer

			logPlaybackKeyLayout(context.Background(), newLayoutLog(&buf), c.roundKeys, c.present, c.err)

			if got := buf.String(); got != c.want+"\n" {
				t.Fatalf("신호 줄 =\n%s\nwant (한 줄)\n%s", got, c.want)
			}
		})
	}
}

// 실 PG — 기동 신호는 회차 불변 트리거를 카탈로그에서 읽는다(있음 · 지운 뒤 없음). 조회가 실패해도(취소된 ctx) 오류를
// 돌려주지 않고 unknown 한 줄을 남긴다 — 트리거는 기동 필수가 아니라 기동은 계속된다(설계 2-2 (다)).
func TestReportPlaybackKeyLayoutReadsTriggerAndNeverStopsBoot(t *testing.T) {
	pool := pgtest.Pool(t, "cmdlayout", index.EnsureSchema, func(context.Context, *pgxpool.Pool) error { return nil })
	ctx := context.Background()
	var presentLog, absentLog, unknownLog bytes.Buffer

	reportPlaybackKeyLayout(ctx, pool, true, newLayoutLog(&presentLog))
	if _, err := pool.Exec(ctx, "DROP TRIGGER stream_sessions_round_frozen ON stream_sessions"); err != nil {
		t.Fatalf("트리거 지우기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := index.EnsureSchema(context.Background(), pool); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})
	reportPlaybackKeyLayout(ctx, pool, true, newLayoutLog(&absentLog))
	cancelled, cancel := context.WithCancel(ctx)
	cancel()
	reportPlaybackKeyLayout(cancelled, pool, false, newLayoutLog(&unknownLog))

	if want := `{"level":"INFO","msg":"playback_key_layout","layout":"round","round_frozen":"present"}` + "\n"; presentLog.String() != want {
		t.Errorf("트리거 있는 DB 신호 = %q, want %q", presentLog.String(), want)
	}
	if want := `{"level":"WARN","msg":"playback_key_layout","layout":"round","round_frozen":"absent"}` + "\n"; absentLog.String() != want {
		t.Errorf("트리거 없는 DB 신호 = %q, want %q", absentLog.String(), want)
	}
	prefix := `{"level":"INFO","msg":"playback_key_layout","layout":"legacy","round_frozen":"unknown","err":"`
	if got := unknownLog.String(); !strings.HasPrefix(got, prefix) || strings.Count(got, "\n") != 1 {
		t.Errorf("조회 오류 신호 = %q, want 한 줄 %s…", got, prefix)
	}
}

// 트리거 조회는 기동 DB 단계와 같은 상한(dbStepTimeout) 안에서 끝난다 — 받기만 하고 답하지 않는 DB 에서도 기동이 걸리지
// 않고 unknown 한 줄을 남긴다. 상한이 20초라 실주행 게이트(REQUIRE_PG=1)에서만 돈다(TestStartExpiryPrivilegeCheckIsBounded 와
// 같은 꼴). 변이: 상한 제거 → 조회가 끝나지 않아 FAIL.
func TestReportPlaybackKeyLayoutIsBounded(t *testing.T) {
	if os.Getenv("REQUIRE_PG") != "1" {
		t.Skip("20초 시험 — REQUIRE_PG=1 실주행 게이트에서만 돈다")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() { // 받기만 하고 답하지 않는 DB
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			t.Cleanup(func() { _ = c.Close() })
		}
	}()
	pool, err := pgxpool.New(t.Context(), "postgres://u@"+ln.Addr().String()+"/db?sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	var buf bytes.Buffer

	done := make(chan struct{})
	go func() {
		defer close(done)
		reportPlaybackKeyLayout(context.Background(), pool, true, newLayoutLog(&buf))
	}()
	select {
	case <-done:
	case <-time.After(dbStepTimeout + 10*time.Second):
		t.Fatal("트리거 조회가 상한 없이 걸려 있다")
	}
	prefix := `{"level":"WARN","msg":"playback_key_layout","layout":"round","round_frozen":"unknown","err":"`
	if got := buf.String(); !strings.HasPrefix(got, prefix) || strings.Count(got, "\n") != 1 {
		t.Errorf("신호 = %q, want 한 줄 %s…", got, prefix)
	}
}

// 기동 신호는 기동 스키마 확인 뒤 · 장부(store) 조립 앞에서 낸다 — 스위치 값은 store 의 키 파생과 같은 cfg 칸이다. run() 은
// DB · 워처가 있어야 돌아 단위 시험이 불가능하므로(boot_test.go 와 같은 사정) 호출 위치를 소스에서 본다.
func TestBootReportsPlaybackKeyLayoutAfterLedgerSchema(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)

	order := []string{
		"requireLedgerSchema(ctx, pool, log)",
		"reportPlaybackKeyLayout(ctx, pool, cfg.PlaybackRoundKeys, log)",
		"playbackKeyFor(cfg.PlaybackRoundKeys, log)",
	}
	prev := -1
	for _, want := range order {
		at := strings.Index(text, want)
		if at < 0 {
			t.Fatalf("%q 를 찾지 못했다 — 기동 신호가 빠졌거나 이름이 바뀌었다", want)
		}
		if at <= prev {
			t.Fatalf("%q 의 위치가 앞선다. 순서는 %v 여야 한다", want, order)
		}
		prev = at
	}
}
