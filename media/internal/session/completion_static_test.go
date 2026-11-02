package session

// 완결 평가 규칙의 정적 단언 — session 몫(POK-196 M6-3b 커밋 ① — 설계 M6 15.2 「완결 평가 네 자리」 가운데 4D
// round_split). round_closed_at 을 쓰는 문장을 보내는 함수는 같은 트랜잭션에서 완결 평가 칸(IntentOptions.CloseRound)을
// 부른다. 나머지 세 자리(CloseRound · TryCloseRound · MarkChainEnded)는 index/finalize_static_test.go 가 본다.

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

func TestRoundClosingStatementsEvaluateCompletion(t *testing.T) {
	paths, err := filepath.Glob("*.go")
	if err != nil {
		t.Fatal(err)
	}
	fset := token.NewFileSet()
	closing := regexp.MustCompile(`SET\s+round_closed_at\s*=`)
	flagged := map[string]bool{}
	var files []*ast.File
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
		files = append(files, f)
		ast.Inspect(f, func(n ast.Node) bool {
			vs, ok := n.(*ast.ValueSpec)
			if !ok {
				return true
			}
			for i, v := range vs.Values {
				if lit, ok := v.(*ast.BasicLit); ok && lit.Kind == token.STRING {
					if s, _ := strconv.Unquote(lit.Value); closing.MatchString(s) {
						flagged[vs.Names[i].Name] = true
					}
				}
			}
			return true
		})
	}
	if len(flagged) == 0 {
		t.Fatal("round_closed_at 을 쓰는 문장 상수가 없다 — round_split 의 R 닫힘 문장이 옮겨졌다")
	}
	users := 0
	for _, f := range files {
		for _, d := range f.Decls {
			fn, ok := d.(*ast.FuncDecl)
			if !ok || fn.Body == nil {
				continue
			}
			uses, evaluates := false, false
			ast.Inspect(fn.Body, func(n ast.Node) bool {
				switch x := n.(type) {
				case *ast.Ident:
					uses = uses || flagged[x.Name]
				case *ast.CallExpr:
					if sel, ok := x.Fun.(*ast.SelectorExpr); ok && sel.Sel.Name == "CloseRound" {
						evaluates = true
					}
				}
				return true
			})
			if !uses {
				continue
			}
			users++
			if !evaluates {
				t.Errorf("%s 가 회차를 닫는데 같은 트랜잭션에서 완결 평가 칸(CloseRound)을 부르지 않는다", fn.Name.Name)
			}
		}
	}
	if users == 0 {
		t.Fatal("회차 닫힘 문장을 보내는 함수가 없다")
	}
}
