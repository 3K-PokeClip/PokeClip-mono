package session

// 완결 평가 규칙의 정적 단언 — session 몫(POK-196 M6-3b 커밋 ① — 설계 M6 15.2 「완결 평가 네 자리」 · POK-525 설계
// r9.3 3-2). 4D 는 회차를 직접 닫지 않는다 — 닫힘은 같은 트랜잭션 끝에서 부르는 완결 평가 칸(IntentOptions.CloseRound)이
// 조건을 보고 한다(upgraded · ending · ended L 의 forwarded). 그래서 이 패키지의 비시험 SQL 에는 round_closed_at 을 쓰는
// 문장이 없어야 한다. 닫는 쪽 세 자리(CloseRound · TryCloseRound · MarkChainEnded)는 index/finalize_static_test.go 가 본다.

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

func TestSessionLeavesRoundClosingToCloseRoundSlot(t *testing.T) {
	paths, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	closing := regexp.MustCompile(`SET\s+round_closed_at\s*=`)
	scanned := 0
	for _, p := range paths {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		src, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		f, err := parser.ParseFile(fset, p, src, 0)
		if err != nil {
			t.Fatal(err)
		}
		scanned++
		ast.Inspect(f, func(n ast.Node) bool {
			lit, ok := n.(*ast.BasicLit)
			if !ok || lit.Kind != token.STRING {
				return true
			}
			if s, _ := strconv.Unquote(lit.Value); closing.MatchString(s) {
				t.Errorf("%s: round_closed_at 을 쓰는 문장이 있다 — 회차 닫힘은 CloseRound 칸(index)이 조건을 보고 한다", fset.Position(lit.Pos()))
			}
			return true
		})
	}
	if scanned == 0 {
		t.Fatal("비시험 Go 파일을 하나도 읽지 않았다")
	}
}
