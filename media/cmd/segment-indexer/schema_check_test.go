package main

import (
	"bytes"
	"context"
	"log/slog"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// 기동 스키마 확인(D50 스키마 몫) — M6 장부 객체가 빠진 DB 면 ERROR schema_not_ready 한 줄과 함께 기동을 멈추고
// (run 이 오류를 내면 main 이 종료 코드 1), 다 있으면 아무것도 남기지 않고 지나간다. 문자열 schema_not_ready 는
// 운영 런북(media-swap.md start-check)이 사이드카 로그에서 세는 계약이다.
func TestRequireLedgerSchemaStopsWithSchemaNotReady(t *testing.T) {
	pool := pgtest.Pool(t, "cmdschema", index.EnsureSchema, func(context.Context, *pgxpool.Pool) error { return nil })
	ctx := context.Background()
	var okLog bytes.Buffer
	okErr := requireLedgerSchema(ctx, pool, slog.New(slog.NewJSONHandler(&okLog, nil)))
	if _, err := pool.Exec(ctx, "DROP TABLE broadcast_lifecycle_outbox"); err != nil {
		t.Fatalf("표 지우기 실패: %v", err)
	}
	t.Cleanup(func() { // 풀보다 늦게 등록 — 풀이 닫히기 전에 돈다
		if err := index.EnsureSchema(context.Background(), pool); err != nil {
			t.Errorf("스키마 되돌리기 실패: %v", err)
		}
	})
	var failLog bytes.Buffer

	failErr := requireLedgerSchema(ctx, pool, slog.New(slog.NewJSONHandler(&failLog, nil)))

	if okErr != nil || okLog.Len() != 0 {
		t.Errorf("다 있는 DB = (%v, 로그 %q), want (nil, 없음)", okErr, okLog.String())
	}
	if failErr == nil || !strings.Contains(failErr.Error(), "schema_not_ready missing=broadcast_lifecycle_outbox") {
		t.Errorf("빠진 DB 오류 = %v, want schema_not_ready missing=broadcast_lifecycle_outbox", failErr)
	}
	line := failLog.String()
	for _, want := range []string{`"level":"ERROR"`, `"msg":"schema_not_ready"`, `"missing":"broadcast_lifecycle_outbox"`} {
		if !strings.Contains(line, want) {
			t.Errorf("빠진 DB 로그 %q 에 %s 가 없다", line, want)
		}
	}
}

// 기동 스키마 확인은 개발 환경 표 생성(EnsureSchema) 뒤 · 장부를 쓰는 store 조립 앞에서 돈다. run() 은 DB · 워처가
// 있어야 돌아 단위 시험이 불가능하므로(boot_test.go 와 같은 사정) 호출 위치를 소스에서 본다 — 호출이 빠지면
// 하한 · 회차 열이 없는 DB 에서 사이드카가 돌다가 핵심 문장에서 깨진다.
func TestBootChecksLedgerSchemaBeforeStore(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)

	order := []string{"index.EnsureSchema(ctx, pool)", "requireLedgerSchema(ctx, pool, log)", "index.NewPGStore("}
	prev := -1
	for _, want := range order {
		at := strings.Index(text, want)
		if at < 0 {
			t.Fatalf("%q 를 찾지 못했다 — 기동 스키마 확인이 빠졌거나 이름이 바뀌었다", want)
		}
		if at <= prev {
			t.Fatalf("%q 의 위치가 앞선다. 순서는 %v 여야 한다", want, order)
		}
		prev = at
	}
}
