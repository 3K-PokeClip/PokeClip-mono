package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// 기동 검사가 거부하면 ERROR timing_downgrade_refused(게이트 값 · 이미지 판 목록)를 남기고 오류를 돌려준다 — run 이 그
// 오류로 끝나 main 이 종료 코드 1 로 나간다(설계 r7 9.3 · 8절). 문자열 timing_downgrade_refused 는 런북(media-swap.md
// 롤백 하한)이 사이드카 로그에서 세는 계약이다. 읽기 실패는 줄 없이 오류만 올린다(기동을 멈추는 것은 같다).
func TestReportTimingGateLogsDowngradeRefused(t *testing.T) {
	two := int16(2)
	refused := &index.TimingDowngradeError{Gate: index.TimingGate{Highest: &two}, Readable: "null"}
	var refusedLog bytes.Buffer

	err := reportTimingGate(context.Background(), slog.New(slog.NewJSONHandler(&refusedLog, nil)), refused)

	if !errors.Is(err, index.ErrTimingDowngrade) {
		t.Errorf("거부 = %v, want ErrTimingDowngrade", err)
	}
	line := refusedLog.String()
	for _, want := range []string{`"level":"ERROR"`, `"msg":"timing_downgrade_refused"`, `"highest_version":"2"`,
		`"new_round_version":"null"`, `"readable":"null"`} {
		if !strings.Contains(line, want) {
			t.Errorf("거부 로그 %q 에 %s 가 없다", line, want)
		}
	}

	readErr := errors.New("읽기 실패")
	var readLog bytes.Buffer
	if err := reportTimingGate(context.Background(), slog.New(slog.NewJSONHandler(&readLog, nil)), readErr); !errors.Is(err, readErr) {
		t.Errorf("읽기 실패 = %v, want 그 오류", err)
	}
	if readLog.Len() != 0 {
		t.Errorf("읽기 실패 로그 = %q, want 없음", readLog.String())
	}
	if err := reportTimingGate(context.Background(), slog.New(slog.NewJSONHandler(&readLog, nil)), nil); err != nil || readLog.Len() != 0 {
		t.Errorf("통과 = (%v, 로그 %q), want (nil, 없음)", err, readLog.String())
	}
}

// 기동 검사는 기동 스키마 확인 뒤 · 장부를 쓰는 store 조립 앞에서 돈다(설계 r7 9.3 「MissingLedgerSchema 다음에」).
// run() 은 DB · 워처가 있어야 돌아 호출 위치를 소스에서 본다. 잡는 결함(m17): 호출이 빠지면 판 2 읽기를 지운 이미지가
// 래치 2 장부에 떠 판 2 행을 옛 식으로 처리한다.
func TestBootChecksTimingGateAfterLedgerSchema(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)
	order := []string{"requireLedgerSchema(ctx, pool, log)", "requireTimingGate(ctx, pool, log)", "index.NewPGStore("}
	prev := -1
	for _, want := range order {
		at := strings.Index(text, want)
		if at < 0 {
			t.Fatalf("%q 를 찾지 못했다 — 기동 검사가 빠졌거나 이름이 바뀌었다", want)
		}
		if at <= prev {
			t.Fatalf("%q 의 위치가 앞선다. 순서는 %v 여야 한다", want, order)
		}
		prev = at
	}
}

// 기동 전 검사 명령 timing-gate-check(설계 r7 9.4)의 종료 코드 — 사이드카 판 목록(SIDECAR_TIMING_READABLE)으로 게이트를 띄워도
// 되면 0, 아니면 3(ERROR timing_downgrade_refused), 검사를 끝내지 못하면 4 다(판 목록 꼴 밖 · 설정 · 연결 · 읽기 실패 —
// fail-closed). 사이드카와 같은 DB 환경 변수(POSTGRES_*)로 실 PG 에 붙는다.
func TestTimingGateCheckExitCodes(t *testing.T) {
	pool := pgtest.Pool(t, "cmdgatecheck", index.EnsureSchema, func(context.Context, *pgxpool.Pool) error { return nil })
	ctx := context.Background()
	setLatch := func(v string) {
		t.Helper()
		if _, err := pool.Exec(ctx, "UPDATE stream_timing_gate SET new_round_version = "+v); err != nil {
			t.Fatalf("스위치 %s 설정 실패: %v", v, err)
		}
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if _, err := pool.Exec(context.Background(),
			"UPDATE stream_timing_gate SET new_round_version = NULL, highest_version = NULL"); err != nil {
			t.Errorf("게이트 되돌리기 실패: %v", err)
		}
	})
	env := pgEnvOf(pool.Config())
	run := func(readable string, args ...string) (int, string) {
		t.Helper()
		var out bytes.Buffer
		getenv := func(k string) string {
			if k == timingReadableEnv {
				return readable
			}
			return env[k]
		}
		return runTimingGateCheck(args, getenv, &out), out.String()
	}

	cases := []struct {
		name, readable, latch string
		args                  []string
		want                  int
		wantLog               string
	}{
		{"게이트 NULL · 출시 판 목록", "null", "", nil, gateCheckPass, ""},
		{"판 목록 꼴 밖(빈 값)", "", "", nil, gateCheckUnchecked, `"msg":"timing_gate_check_failed"`},
		{"인자 더 있음", "null", "", []string{"x"}, gateCheckUnchecked, `"msg":"timing_gate_check_failed"`},
		{"래치 2 · 출시 판 목록", "null", "2", nil, gateCheckRefused, `"msg":"timing_downgrade_refused"`},
		{"래치 2 · 스위치 NULL · 출시 판 목록", "null", "NULL", nil, gateCheckRefused, `"highest_version":"2"`},
		{"래치 2 · c0 판 목록", "null,2", "2", nil, gateCheckPass, ""},
	}
	for _, c := range cases {
		if c.latch != "" {
			setLatch(c.latch)
		}
		code, log := run(c.readable, c.args...)
		if code != c.want || (c.wantLog != "" && !strings.Contains(log, c.wantLog)) {
			t.Errorf("%s: 종료 코드 %d · 로그 %q, want %d · %s", c.name, code, log, c.want, c.wantLog)
		}
	}

	env["POSTGRES_PORT"] = "1"
	if code, log := run("null"); code != gateCheckUnchecked || !strings.Contains(log, `"msg":"timing_gate_check_failed"`) {
		t.Errorf("DB 연결 실패: 종료 코드 %d · 로그 %q, want %d · timing_gate_check_failed", code, log, gateCheckUnchecked)
	}
	delete(env, "POSTGRES_USER")
	if code, _ := run("null"); code != gateCheckUnchecked {
		t.Errorf("DB 설정 빠짐: 종료 코드 %d, want %d", code, gateCheckUnchecked)
	}
}

// 검사 명령이 게이트 SELECT 를 잃은 media_indexer 로 돌면 읽기 실패(42501)라 4 다 — 막힌다(fail-closed).
func TestTimingGateCheckUnreadableWithoutGateSelect(t *testing.T) {
	admin := pgtest.Pool(t, "cmdgaterole", index.EnsureSchema, func(context.Context, *pgxpool.Pool) error { return nil })
	role := pgtest.NewMediaIndexerRole(t, admin)
	var out bytes.Buffer
	log := slog.New(slog.NewJSONHandler(&out, nil))
	readable := []*int16{nil}
	if code := checkGateCode(context.Background(), role.Pool, readable, log); code != gateCheckPass {
		t.Fatalf("media_indexer 검사 = %d, want %d — 로그 %q", code, gateCheckPass, out.String())
	}

	role.Revoke(t, "SELECT", "stream_timing_gate")
	code := checkGateCode(context.Background(), role.Pool, readable, log)

	if code != gateCheckUnchecked || !strings.Contains(out.String(), "42501") {
		t.Errorf("게이트 SELECT 회수 뒤 = %d · 로그 %q, want %d · 42501", code, out.String(), gateCheckUnchecked)
	}
}

// pgEnvOf 는 시험 풀 설정을 사이드카 DB 환경 변수(pgconf.FromEnv — 비밀번호 로그인 · TLS 끔)로 옮긴다.
func pgEnvOf(c *pgxpool.Config) map[string]string {
	return map[string]string{
		"POSTGRES_USER":     c.ConnConfig.User,
		"POSTGRES_PASSWORD": c.ConnConfig.Password,
		"POSTGRES_HOST":     c.ConnConfig.Host,
		"POSTGRES_PORT":     fmt.Sprint(c.ConnConfig.Port),
		"POSTGRES_DB":       c.ConnConfig.Database,
		"POSTGRES_SSLMODE":  "disable",
	}
}
