package endsignal

import (
	"go/ast"
	"go/parser"
	"go/token"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/streamkey"
)

// 계약4 3-1 시험 벡터 — 파생 값은 HMAC-SHA256(키 = passphrase, 메시지 = "pokeclip-4d-v1") 의 소문자 16진 64자다.
func TestVerifyBearerContractVector(t *testing.T) {
	p, err := streamkey.NewPassphrase(testPassphrase)
	if err != nil {
		t.Fatal(err)
	}
	if !verifyBearer(p, testBearer) {
		t.Fatalf("시험 벡터가 맞지 않는다")
	}
	if verifyBearer(p, wrongBearer) {
		t.Fatalf("틀린 파생 값이 맞았다")
	}
	other, _ := streamkey.NewPassphrase("Synthetic_Pass-phrase_0123456789")
	if verifyBearer(other, testBearer) {
		t.Fatalf("다른 passphrase 로 같은 파생 값이 맞았다")
	}
}

// parseBearer 는 스킴 Bearer(대소문자 무관) · 공백 하나 · 16진 64자(대소문자 무관)만 받고 소문자로 돌려준다.
func TestParseBearer(t *testing.T) {
	upper := strings.ToUpper(testBearer)
	tests := []struct {
		in     string
		want   string
		wantOK bool
	}{
		{in: "Bearer " + testBearer, want: testBearer, wantOK: true},
		{in: "bearer " + upper, want: testBearer, wantOK: true},
		{in: "BEARER " + testBearer, want: testBearer, wantOK: true},
		{in: ""},
		{in: "Bearer"},
		{in: "Bearer "},
		{in: "Bearer  " + testBearer},
		{in: "Bearer\t" + testBearer},
		{in: "Basic " + testBearer},
		{in: "Bearer " + testBearer[:63]},
		{in: "Bearer " + testBearer + "a"},
		{in: "Bearer " + testBearer[:63] + "z"},
		{in: "Bearer " + testBearer + " "},
		{in: " Bearer " + testBearer},
	}
	for _, tc := range tests {
		got, ok := parseBearer(tc.in)
		if got != tc.want || ok != tc.wantOK {
			t.Errorf("parseBearer(%q) = (%q, %v), want (%q, %v)", tc.in, got, ok, tc.want, tc.wantOK)
		}
	}
}

// P7 — 파생 값 대조는 상수 시간 hmac.Equal 하나다. verifyBearer 안에 == · != 비교가 없어야 한다(정적 단언).
func TestVerifyBearerUsesConstantTimeCompareP7(t *testing.T) {
	file, err := parser.ParseFile(token.NewFileSet(), "auth.go", nil, 0)
	if err != nil {
		t.Fatalf("auth.go 해석 실패: %v", err)
	}
	var fn *ast.FuncDecl
	for _, d := range file.Decls {
		if f, ok := d.(*ast.FuncDecl); ok && f.Name.Name == "verifyBearer" {
			fn = f
		}
	}
	if fn == nil {
		t.Fatalf("verifyBearer 가 없다")
	}
	equals, compares := 0, 0
	ast.Inspect(fn.Body, func(n ast.Node) bool {
		switch x := n.(type) {
		case *ast.CallExpr:
			if sel, ok := x.Fun.(*ast.SelectorExpr); ok {
				if id, ok := sel.X.(*ast.Ident); ok && id.Name == "hmac" && sel.Sel.Name == "Equal" {
					equals++
				}
			}
		case *ast.BinaryExpr:
			if x.Op == token.EQL || x.Op == token.NEQ {
				compares++
			}
		}
		return true
	})
	if equals != 1 || compares != 0 {
		t.Fatalf("verifyBearer: hmac.Equal %d 번 · ==/!= %d 번, want 1 · 0", equals, compares)
	}
}
