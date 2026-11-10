package index

// 회차 불변의 코드 몫 정적 단언(POK-525 설계 r9.3 2-2 · 8-2 E9 · 계획 Ⓑ1-3). 새 판의 정합은 트리거가 아니라 코드가
// 지킨다 — media 모듈의 비시험 Go 문자열에 round_id 를 SET 하는 UPDATE(ON CONFLICT … DO UPDATE 포함)가 없어야 한다.
// 트리거는 운영에서 늦게 깔리고(contract) 기동 필수도 아니므로, 이 단언이 깨지면 그 전 구간의 정합이 없다.

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
)

// mediaModuleRoot 는 이 패키지 디렉터리에서 본 media 모듈 뿌리다.
var mediaModuleRoot = filepath.Join("..", "..")

// updateSetClause 는 UPDATE … SET 의 SET 절이다 — 다음 WHERE · FROM · RETURNING 이나 문자열 끝까지.
var updateSetClause = regexp.MustCompile(`(?is)\bUPDATE\b.*?\bSET\b(.*?)(?:\bWHERE\b|\bFROM\b|\bRETURNING\b|$)`)

// setsRoundID 는 SET 절이 round_id 에 값을 넣는가다 — 한 열(round_id =)과 여러 열 묶음((…, round_id, …) =) 둘 다.
var setsRoundID = regexp.MustCompile(`(?is)(?:^|[\s,(])round_id\s*=|\([^)]*\bround_id\b[^)]*\)\s*=`)

// sqlSetsRoundID 는 s 의 UPDATE 가운데 round_id 를 SET 하는 것이 있는가다.
func sqlSetsRoundID(s string) bool {
	for _, m := range updateSetClause.FindAllStringSubmatch(s, -1) {
		if setsRoundID.MatchString(m[1]) {
			return true
		}
	}
	return false
}

// 판별기 자체의 교정 — 지운 moveRoundSQL 꼴 · 여러 열 묶음 · upsert 는 잡고, round_id 를 조건으로만 쓰는 회차 닫힘 ·
// round_closed_at 갱신은 잡지 않는다.
func TestSQLSetsRoundIDDetector(t *testing.T) {
	for _, tt := range []struct {
		sql  string
		want bool
	}{
		{"UPDATE stream_sessions SET round_id = $2, stop_marked_at = NULL WHERE session_id = ANY($1::text[])", true},
		{"UPDATE stream_sessions SET stop_marked_at = NULL,round_id=$2 WHERE session_id = $1", true},
		{"UPDATE stream_sessions SET (state, round_id) = ('ended', $2) WHERE session_id = $1", true},
		{"INSERT INTO stream_sessions (session_id, round_id) VALUES ($1, $2) ON CONFLICT (session_id) DO UPDATE SET round_id = EXCLUDED.round_id", true},
		{"UPDATE stream_sessions SET round_closed_at = pg_catalog.now() WHERE round_id = $1 AND round_closed_at IS NULL", false},
		{"UPDATE stream_sessions s SET stop_marked_at = now() FROM x WHERE s.round_id = x.round_id", false},
		{"INSERT INTO stream_sessions (session_id, round_id) VALUES ($1, $2)", false},
	} {
		if got := sqlSetsRoundID(tt.sql); got != tt.want {
			t.Errorf("sqlSetsRoundID(%q) = %v, want %v", tt.sql, got, tt.want)
		}
	}
}

// E9 코드 몫 — media 모듈의 비시험 Go 문자열에 round_id 를 SET 하는 UPDATE 가 없다. 변이: moveRoundSQL 되살림 → FAIL.
func TestMediaSQLNeverSetsRoundID(t *testing.T) {
	fset := token.NewFileSet()
	scanned := 0
	err := filepath.WalkDir(mediaModuleRoot, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() || !strings.HasSuffix(p, ".go") || strings.HasSuffix(p, "_test.go") {
			return nil
		}
		src, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		f, err := parser.ParseFile(fset, p, src, 0)
		if err != nil {
			return err
		}
		scanned++
		ast.Inspect(f, func(n ast.Node) bool {
			lit, ok := n.(*ast.BasicLit)
			if !ok || lit.Kind != token.STRING {
				return true
			}
			if s, _ := strconv.Unquote(lit.Value); sqlSetsRoundID(s) {
				t.Errorf("%s: round_id 를 SET 하는 문장이 있다 — 세션의 회차는 INSERT 뒤 바뀌지 않는다(POK-525)", fset.Position(lit.Pos()))
			}
			return true
		})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if scanned == 0 {
		t.Fatal("비시험 Go 파일을 하나도 읽지 않았다")
	}
}
