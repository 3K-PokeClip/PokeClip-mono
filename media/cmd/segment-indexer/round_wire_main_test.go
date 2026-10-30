package main

// 회차별 조회의 run 배선(POK-196 M6-3b 커밋 ⑤ · 설계 M6 11절 「M6 몫 2 — round 인자 채우기」).

import (
	"os"
	"strings"
	"testing"
)

// run 은 제어면 조립의 round 칸에 인덱서 풀(pool)로 만든 회차 조회를 넣는다 — nil 이면 회차 경로가 미등록(404)으로 남아 웹이
// 끝남 대기에서 다시보기를 영영 모른다. 발행자 풀(pubPool)을 넣으면 강등 · 꺼짐에서 풀이 없다. run() 은 DB 가 있어야 돌아
// 단위 시험이 불가능하므로(TestShutdownDeferRegistrationOrder) 소스에서 본다.
func TestRunWiresRoundLookupOnIndexerPool(t *testing.T) {
	src, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatalf("main.go 를 읽지 못했다: %v", err)
	}
	text := string(src)
	const call = "startControlWithEndSignal(cfg, board, log, control.NewRoundLookup(pool, cfg.RewindPublicBaseURL, log), recv)"
	if n := strings.Count(text, "startControlWithEndSignal("); n != 1 || !strings.Contains(text, call) {
		t.Fatalf("main.go 의 startControlWithEndSignal 호출 %d번, want %q 한 번 — round 칸이 nil 이거나 다른 풀이다", n, call)
	}
}
