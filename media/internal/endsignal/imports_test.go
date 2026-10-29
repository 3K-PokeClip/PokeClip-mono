package endsignal

import (
	"go/parser"
	"go/token"
	"os"
	"strconv"
	"strings"
	"testing"
)

// D11 — 수신 고루틴은 되감기 캐시를 만지지 않는다(캐시는 루프 전용). 효과는 DB 문장(Apply)과 받은편지함 표지 · 해제
// 요청뿐이다. 제품 파일이 되감기 패키지(internal/rewind/...)를 임포트하지 않음을 정적으로 단언한다.
func TestReceiverDoesNotImportRewindD11(t *testing.T) {
	entries, err := os.ReadDir(".")
	if err != nil {
		t.Fatal(err)
	}
	const forbidden = "github.com/3K-PokeClip/pokeclip-mono/media/internal/rewind"
	checked := 0
	for _, e := range entries {
		name := e.Name()
		if !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") {
			continue
		}
		f, err := parser.ParseFile(token.NewFileSet(), name, nil, parser.ImportsOnly)
		if err != nil {
			t.Fatalf("%s 해석 실패: %v", name, err)
		}
		checked++
		for _, imp := range f.Imports {
			path, _ := strconv.Unquote(imp.Path.Value)
			if path == forbidden || strings.HasPrefix(path, forbidden+"/") {
				t.Errorf("%s 가 %s 를 임포트한다 — 캐시는 루프 전용이다", name, path)
			}
		}
	}
	if checked == 0 {
		t.Fatalf("검사한 제품 파일이 없다")
	}
}
