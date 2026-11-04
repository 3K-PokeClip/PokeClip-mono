package main

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/index"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
	"github.com/3K-PokeClip/pokeclip-mono/media/internal/upload"
)

// ③ 사슬 복원 배선(POK-196 PDT 계획 4절 c10b) — 업로더에 넘기는 장부가 UploadStore 와 복원 조회 둘(ChainLedger)을 함께
// 갖는다. 업로더는 장부가 ChainLedger 일 때만 복원하므로, 배선이 빠지면 재기동 뒤 ③ 사슬이 조용히 되살아나지 않는다.
func TestUploadStoreWiresChainLedger(t *testing.T) {
	var st index.UploadStore = newUploadStore(nil)

	if _, ok := st.(upload.ChainLedger); !ok {
		t.Fatalf("업로더 장부 %T 가 upload.ChainLedger 가 아니다", st)
	}
}

// 조회 둘은 index 의 패키지 함수(LoadPlaybackHoles · LoadPlaybackChainRows)를 그대로 부른다 — 빈 장부에서 빈 결과 · 오류 없음.
func TestChainLedgerQueriesRunAgainstLedger(t *testing.T) {
	pool := pgtest.Pool(t, "cmdrestore", index.EnsureSchema, func(context.Context, *pgxpool.Pool) error { return nil })
	l := newUploadStore(pool).(upload.ChainLedger)

	holes, herr := l.PlaybackHoles(context.Background(), "S-none")
	rows, rerr := l.PlaybackChainRows(context.Background(), "demo", 0, 10)

	if herr != nil || rerr != nil || len(holes)+len(rows) != 0 {
		t.Errorf("빈 장부 조회 = (%v, %v) · (%v, %v), want 빈 결과 · 오류 없음", holes, herr, rows, rerr)
	}
}

// 기동 배선이 그 장부를 업로더에 넘긴다 — run() 은 DB · 워처가 있어야 돌아 소스에서 본다(boot_test.go 와 같은 사정).
func TestBootPassesRestoreStoreToUploader(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	if !strings.Contains(string(src), "newUploader(ctx, cfg, root, newUploadStore(pool), log)") {
		t.Error("main.go 가 업로더에 newUploadStore(pool) 를 넘기지 않는다")
	}
}
