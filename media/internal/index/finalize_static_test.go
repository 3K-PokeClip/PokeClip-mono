package index

// 종료 문장의 정적 단언(POK-196 M6-3b 커밋 ① — 설계 M6 15.2 완결 평가 규칙 · 3.3 사슬 간선 규칙 · 위상 잠금 글자).
// 「구조가 곧 단정」을 소스 수준에서 고정한다(seed_static_test.go 와 같은 꼴) — PG 없이 돈다.

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

// packageAST 는 이 패키지의 제품 파일(시험 제외)을 해석한다.
func packageAST(t *testing.T) []*ast.File {
	t.Helper()
	fset := token.NewFileSet()
	var files []*ast.File
	for p := range packageSources(t) {
		f, err := parser.ParseFile(fset, p, nil, 0)
		if err != nil {
			t.Fatalf("%s 해석 실패: %v", p, err)
		}
		files = append(files, f)
	}
	return files
}

// constValues 는 패키지 문자열 상수의 값이다 — 리터럴과 다른 상수를 + 로 이은 식을 펼친다. refs 는 상수마다 그 식이
// 가리키는 다른 상수 이름이다.
func constValues(t *testing.T, files []*ast.File) (values map[string]string, refs map[string][]string) {
	t.Helper()
	exprs := map[string]ast.Expr{}
	for _, f := range files {
		for _, d := range f.Decls {
			g, ok := d.(*ast.GenDecl)
			if !ok || g.Tok != token.CONST {
				continue
			}
			for _, s := range g.Specs {
				vs := s.(*ast.ValueSpec)
				for i, n := range vs.Names {
					if i < len(vs.Values) {
						exprs[n.Name] = vs.Values[i]
					}
				}
			}
		}
	}
	values, refs = map[string]string{}, map[string][]string{}
	var eval func(name string, e ast.Expr) (string, bool)
	eval = func(name string, e ast.Expr) (string, bool) {
		switch x := e.(type) {
		case *ast.BasicLit:
			if x.Kind != token.STRING {
				return "", false
			}
			v, err := strconv.Unquote(x.Value)
			return v, err == nil
		case *ast.Ident:
			refs[name] = append(refs[name], x.Name)
			if other, ok := exprs[x.Name]; ok {
				return eval(x.Name, other)
			}
			return "", false
		case *ast.BinaryExpr:
			l, ok1 := eval(name, x.X)
			r, ok2 := eval(name, x.Y)
			return l + r, ok1 && ok2 && x.Op == token.ADD
		case *ast.ParenExpr:
			return eval(name, x.X)
		}
		return "", false
	}
	for n, e := range exprs {
		if v, ok := eval(n, e); ok {
			values[n] = v
		}
	}
	return values, refs
}

// funcCalls 는 함수 본문이 쓰는 식별자와 부르는 함수 이름(식별자 · 선택자 끝 이름)이다.
func funcCalls(fn *ast.FuncDecl) (idents, calls map[string]bool) {
	idents, calls = map[string]bool{}, map[string]bool{}
	ast.Inspect(fn.Body, func(n ast.Node) bool {
		switch x := n.(type) {
		case *ast.Ident:
			idents[x.Name] = true
		case *ast.CallExpr:
			switch f := x.Fun.(type) {
			case *ast.Ident:
				calls[f.Name] = true
			case *ast.SelectorExpr:
				calls[f.Sel.Name] = true
			}
		}
		return true
	})
	return idents, calls
}

// 완결 평가 규칙(15.2) — round_closed_at 을 쓰거나 회차 세션을 ended 로 바꾸는 문장을 보내는 함수는 같은 함수 안에서
// 완결 평가(EndedEnqueuer 칸 enqueue · 또는 그것을 부르는 TryCloseRound)를 부른다. 그런 문장이 이 패키지에 둘
// (회차 닫힘 · phase 4)이고 각각 쓰는 함수가 있어야 한다 — 0 이면 규칙이 아무것도 지키지 않는다.
func TestCompletionEvaluatedWhereRoundClosesOrChainEnds(t *testing.T) {
	files := packageAST(t)
	values, _ := constValues(t, files)
	completing := regexp.MustCompile(`SET\s+(state\s*=\s*'ended'|round_closed_at\s*=)`)
	flagged := map[string]bool{}
	for n, v := range values {
		if completing.MatchString(v) {
			flagged[n] = true
		}
	}
	if len(flagged) != 2 {
		t.Fatalf("완결을 만드는 문장 상수 = %v, want 둘(회차 닫힘 · phase 4)", flagged)
	}
	users := 0
	for _, f := range files {
		for _, d := range f.Decls {
			fn, ok := d.(*ast.FuncDecl)
			if !ok || fn.Body == nil {
				continue
			}
			idents, calls := funcCalls(fn)
			for c := range flagged {
				if !idents[c] {
					continue
				}
				users++
				if !calls["enqueue"] && !calls["TryCloseRound"] {
					t.Errorf("%s 가 %s 를 보내는데 같은 트랜잭션에서 완결 평가(enqueue · TryCloseRound)를 부르지 않는다", fn.Name.Name, c)
				}
				if fn.Name.Name == "TryCloseRound" && !calls["enqueue"] {
					t.Errorf("TryCloseRound 가 enqueue 를 부르지 않는다 — 다른 자리가 그것에 기댄다")
				}
			}
		}
	}
	if users < 2 {
		t.Fatalf("완결 문장을 보내는 함수가 %d 개다 — 문장 상수를 함수 밖 도우미로 옮기면 규칙이 비어 버린다", users)
	}
}

// 사슬 간선 규칙(3.3) — 간선 글자는 chainEdgeSQL 한 벌이고, 사슬 계산(LoadChainLedger · CloseChain) · 정산 스캔과
// 빠른 길의 후속 판정(HasSuccessor)이 그 상수를 끼워 쓴다. 다른 리터럴이 간선을 따로 적으면 세 호출자가 갈린다.
func TestChainEdgeConstantSharedByCallers(t *testing.T) {
	files := packageAST(t)
	values, refs := constValues(t, files)
	if !strings.Contains(values["chainEdgeSQL"], "c.inherits_session = p.session_id") {
		t.Fatalf("chainEdgeSQL = %q — 간선 글자가 바뀌었다", values["chainEdgeSQL"])
	}
	for _, user := range []string{"chainMembersSQL", "endingRowSelectSQL"} {
		found := false
		for _, r := range refs[user] {
			found = found || r == "chainEdgeSQL"
		}
		if !found {
			t.Errorf("%s 가 chainEdgeSQL 을 끼워 쓰지 않는다", user)
		}
	}
	for _, user := range []string{"listEndingSessionsSQL", "loadEndingSessionSQL"} {
		if !strings.Contains(values[user], values["chainEdgeSQL"]) {
			t.Errorf("%s 의 후속 판정이 chainEdgeSQL 과 다르다", user)
		}
	}
	edge := regexp.MustCompile(`inherits_session\s*=\s*p\.session_id`)
	for _, f := range files {
		ast.Inspect(f, func(n ast.Node) bool {
			lit, ok := n.(*ast.BasicLit)
			if !ok || lit.Kind != token.STRING {
				return true
			}
			v, _ := strconv.Unquote(lit.Value)
			if edge.MatchString(v) && v != values["chainEdgeSQL"] {
				t.Errorf("chainEdgeSQL 밖 리터럴이 사슬 간선을 따로 적는다: %.80q", v)
			}
			return true
		})
	}
}

// 위상 잠금 글자(3.3) — index 의 종료 문장과 session 의 4D 가 같은 스트림에서 서로를 줄 세우려면 advisory 열쇠가 같아야
// 한다. index 는 session 을 임포트하지 않아 글자를 따로 들므로 두 원문을 대조한다(db m63a M2 「패키지 간 대조」).
func TestTopologyLockLettersMatchSession(t *testing.T) {
	src, err := os.ReadFile("../session/intentional.go")
	if err != nil {
		t.Fatal(err)
	}
	m := regexp.MustCompile("const topologyLockSQL = `([^`]*)`").FindSubmatch(src)
	if m == nil {
		t.Fatal("session 의 topologyLockSQL 을 찾지 못했다")
	}
	if string(m[1]) != topologyLockSQL {
		t.Fatalf("위상 잠금 글자가 갈렸다\n index   %q\n session %q", topologyLockSQL, m[1])
	}
	if !strings.Contains(topologyLockSQL, "'pc_topology_'") {
		t.Fatalf("이름 공간 pc_topology_ 가 없다: %q", topologyLockSQL)
	}
}
