package index

// prod SQL 사본 대조 시험(POK-304 · ADR-0001 추기 2026-10-23) — PG 없이 돈다.
//
// 운영 DB(RDS)의 media 계정 · stream_* 스키마는 infra/terraform/prod/runtime/sql/ 의 psql 스크립트로
// 적용한다. 그 SQL 은 media 쪽 정본의 사본이다: 스키마는 ddl.go 의 schemaDDL, 권한은
// pgtest.MediaIndexerGrantSQL(사이드카) · lcRoleGrants(janitor). 사본이 정본과 갈라지면 이 시험이 깨진다.
// 실제 적용(비superuser 마스터 대역 · H2 · H5)은 prod_sql_apply_pg_test.go 가 잰다.

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// prodSQLDir 은 이 패키지 디렉터리에서 본 prod SQL 디렉터리다. media-ci 의 paths 에 같은 경로가 있다 —
// 그 줄이 없으면 SQL 만 고치는 PR 에서 이 시험이 돌지 않는다.
var prodSQLDir = filepath.Join("..", "..", "..", "infra", "terraform", "prod", "runtime", "sql")

// prodSQLApplied 는 운영자가 순서대로 psql 로 돌리는 파일이다(00 ~ 40 은 psql -1, 50 은 따로).
var prodSQLApplied = []string{
	"00_preflight.sql",
	"10_roles.sql",
	"20_schema.sql",
	"30_grants.sql",
	"40_verify.sql",
	"50_advisory_probe.sql",
}

// prodSchemaFile 은 schemaDDL 사본이다. 20_schema.sql 이 \ir 로 읽고, 혼자서는 돌리지 않는다.
const prodSchemaFile = "stream_schema.sql"

// stream_schema.sql 에서 schemaDDL 사본이 시작하고 끝나는 표지다. 두 표지 사이 글자는 schemaDDL 과 바이트가 같다.
const (
	schemaCopyBegin = "-- BEGIN schemaDDL"
	schemaCopyEnd   = "-- END schemaDDL"
)

// 허용한 psql 메타 명령 줄이다. 이 밖의 메타 명령(\i · \gset · \if 등)과 psql 변수는 쓰지 않는다 — 실적용
// 시험의 처리기가 이 둘만 알아서, 그 밖의 것은 psql 과 다르게 돌 수 있다.
const (
	metaStopOnError = `\set ON_ERROR_STOP on`
	metaIncludeDDL  = `\ir ` + prodSchemaFile
)

// prodSQLAllowedMeta 는 파일마다 허용한 메타 명령 줄이다. 없는 파일은 메타 명령을 하나도 쓰지 않는다.
var prodSQLAllowedMeta = map[string][]string{
	"00_preflight.sql":      {metaStopOnError},
	"10_roles.sql":          {metaStopOnError},
	"20_schema.sql":         {metaStopOnError, metaIncludeDDL},
	"30_grants.sql":         {metaStopOnError},
	"40_verify.sql":         {metaStopOnError},
	"50_advisory_probe.sql": {metaStopOnError},
}

// 30_grants.sql 이 GRANT 를 감싸는 두 문장이다(H2 — 마스터는 INHERIT FALSE 라 media_owner 로 바꿔야 GRANT 할 수 있다).
const (
	grantsSetRole   = "SET ROLE media_owner"
	grantsResetRole = "RESET ROLE"
)

// readProdSQL 은 prod SQL 파일 name 의 내용이다.
func readProdSQL(t *testing.T, name string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(prodSQLDir, name))
	if err != nil {
		t.Fatalf("prod SQL 파일을 읽지 못했다: %v", err)
	}
	return string(b)
}

// psqlScript 는 psql 스크립트를 최상위 문장과 메타 명령으로 가른 것이다.
type psqlScript struct {
	// statements 는 끝 ; 를 떼고 주석을 뺀 최상위 문장이다. 따옴표 · 달러 따옴표 안의 ; 와 BEGIN 은
	// 문장을 가르지 않는다(DO 블록 본문이 문장 하나에 남는다).
	statements []string
	// metas 는 따옴표 · 주석 밖의 백슬래시에서 줄 끝까지다(앞뒤 공백 없음).
	metas []string
}

// dollarTag 는 달러 따옴표 여는 표지($$ · $tag$)다. $1 같은 자리표시자는 숫자로 시작해 맞지 않는다.
var dollarTag = regexp.MustCompile(`^\$([A-Za-z_][A-Za-z0-9_]*)?\$`)

// splitPSQL 은 src 를 최상위 문장과 메타 명령으로 가른다. 닫히지 않은 따옴표 · 주석은 오류다.
func splitPSQL(src string) (psqlScript, error) {
	var out psqlScript
	var cur strings.Builder
	flush := func() {
		if s := strings.TrimSpace(cur.String()); s != "" {
			out.statements = append(out.statements, s)
		}
		cur.Reset()
	}
	for i := 0; i < len(src); {
		rest := src[i:]
		switch {
		case strings.HasPrefix(rest, "--"):
			i += lineLen(rest) // 줄바꿈은 남긴다 — 토큰이 붙지 않게
		case strings.HasPrefix(rest, "/*"):
			end := strings.Index(rest[2:], "*/")
			if end < 0 {
				return psqlScript{}, fmt.Errorf("%d 바이트의 /* 주석이 닫히지 않았다", i)
			}
			i += 2 + end + 2
		case rest[0] == '\'' || rest[0] == '"':
			n := quotedLen(rest)
			if n < 0 {
				return psqlScript{}, fmt.Errorf("%d 바이트의 따옴표가 닫히지 않았다", i)
			}
			cur.WriteString(rest[:n])
			i += n
		case dollarTag.MatchString(rest):
			tag := dollarTag.FindString(rest)
			end := strings.Index(rest[len(tag):], tag)
			if end < 0 {
				return psqlScript{}, fmt.Errorf("%d 바이트의 달러 따옴표 %s 가 닫히지 않았다", i, tag)
			}
			n := len(tag) + end + len(tag)
			cur.WriteString(rest[:n])
			i += n
		case rest[0] == '\\':
			n := lineLen(rest)
			out.metas = append(out.metas, strings.TrimSpace(rest[:n]))
			i += n
		case rest[0] == ';':
			flush()
			i++
		default:
			cur.WriteByte(rest[0])
			i++
		}
	}
	flush()
	return out, nil
}

// lineLen 은 s 에서 줄바꿈 앞까지의 길이다(줄바꿈이 없으면 s 전체).
func lineLen(s string) int {
	if n := strings.IndexByte(s, '\n'); n >= 0 {
		return n
	}
	return len(s)
}

// quotedLen 은 s[0] 의 따옴표로 시작한 토큰의 길이다(같은 따옴표 두 개가 이어지면 글자 하나다). 닫히지 않으면 -1 이다.
func quotedLen(s string) int {
	q := s[0]
	for j := 1; j < len(s); j++ {
		if s[j] != q {
			continue
		}
		if j+1 < len(s) && s[j+1] == q {
			j++
			continue
		}
		return j + 1
	}
	return -1
}

// mustSplitPSQL 은 prod SQL 파일 name 을 가른다.
func mustSplitPSQL(t *testing.T, name string) psqlScript {
	t.Helper()
	s, err := splitPSQL(readProdSQL(t, name))
	if err != nil {
		t.Fatalf("splitPSQL(%s) 오류 = %v", name, err)
	}
	return s
}

// normalizeSQL 은 공백 덩어리를 빈칸 하나로 접는다.
func normalizeSQL(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

// firstKeyword 는 문장의 첫 낱말(대문자)이다.
func firstKeyword(stmt string) string {
	f := strings.Fields(stmt)
	if len(f) == 0 {
		return ""
	}
	return strings.ToUpper(f[0])
}

// txKeywords 는 트랜잭션을 열고 닫는 최상위 문장의 첫 낱말이다.
var txKeywords = []string{"ABORT", "BEGIN", "COMMIT", "END", "PREPARE", "RELEASE", "ROLLBACK", "SAVEPOINT", "START"}

// 디렉터리의 .sql 은 적용 목록 + 스키마 사본이 전부다 — 목록 밖 파일은 적용 시험도 운영 순서도 돌지 않는다.
func TestProdSQLDirHoldsOnlyKnownFiles(t *testing.T) {
	got, err := filepath.Glob(filepath.Join(prodSQLDir, "*.sql"))
	if err != nil {
		t.Fatal(err)
	}
	for i := range got {
		got[i] = filepath.Base(got[i])
	}
	want := append(slices.Clone(prodSQLApplied), prodSchemaFile)
	slices.Sort(got)
	slices.Sort(want)
	if !slices.Equal(got, want) {
		t.Errorf("%s 의 .sql = %v, want %v", prodSQLDir, got, want)
	}
}

// stream_schema.sql 의 두 표지 사이가 schemaDDL 과 바이트가 같다(D8).
func TestProdStreamSchemaCopyMatchesSchemaDDL(t *testing.T) {
	src := readProdSQL(t, prodSchemaFile)
	if n := strings.Count(src, schemaCopyBegin); n != 1 {
		t.Fatalf("%s 의 시작 표지 %q 개수 = %d, want 1", prodSchemaFile, schemaCopyBegin, n)
	}
	if n := strings.Count(src, schemaCopyEnd); n != 1 {
		t.Fatalf("%s 의 끝 표지 %q 개수 = %d, want 1", prodSchemaFile, schemaCopyEnd, n)
	}
	start := strings.Index(src, schemaCopyBegin) + len(schemaCopyBegin)
	end := strings.Index(src, schemaCopyEnd)
	if end < start {
		t.Fatalf("%s 의 끝 표지가 시작 표지보다 앞에 있다", prodSchemaFile)
	}
	got := src[start:end]
	if got == schemaDDL {
		return
	}
	i := 0
	for i < len(got) && i < len(schemaDDL) && got[i] == schemaDDL[i] {
		i++
	}
	line := strings.Count(schemaDDL[:i], "\n") + 1
	t.Errorf("%s 사본 = %d 바이트, schemaDDL = %d 바이트 — schemaDDL 의 %d 번째 줄(%d 바이트)에서 처음 갈라진다. "+
		"ddl.go 를 바꿨으면 표지 사이를 schemaDDL 그대로 다시 붙여 넣는다", prodSchemaFile, len(got), len(schemaDDL), line, i)
}

// grantTarget 은 GRANT 문장 끝의 대상 역할이다.
var grantTarget = regexp.MustCompile(`(?i)\sTO\s+(\S+)$`)

// 30_grants.sql 의 GRANT 를 대상 역할별로 모은 집합이 정본 두 목록과 같다. 그 밖의 문장은 감싸는
// SET ROLE · RESET ROLE 둘뿐이다(ALTER DEFAULT PRIVILEGES 등은 쓰지 않는다 — K4).
func TestProdGrantsMatchCanonicalRoleLists(t *testing.T) {
	stmts := mustSplitPSQL(t, "30_grants.sql").statements
	if len(stmts) < 2 || normalizeSQL(stmts[0]) != grantsSetRole || normalizeSQL(stmts[len(stmts)-1]) != grantsResetRole {
		t.Fatalf("30_grants.sql 의 처음 · 끝 문장 = %q · %q, want %q · %q (H2)", firstStmt(stmts), lastStmt(stmts), grantsSetRole, grantsResetRole)
	}
	got := map[string][]string{}
	for _, s := range stmts[1 : len(stmts)-1] {
		s = normalizeSQL(s)
		m := grantTarget.FindStringSubmatch(s)
		if firstKeyword(s) != "GRANT" || m == nil {
			t.Errorf("30_grants.sql 의 문장 %q 는 GRANT … TO <역할> 이 아니다", s)
			continue
		}
		got[m[1]] = append(got[m[1]], s)
	}
	want := map[string][]string{
		"media_indexer": pgtest.MediaIndexerGrantSQL("media_indexer"),
		"media_janitor": lcRoleGrants("media_janitor"),
	}
	for role := range got {
		if _, ok := want[role]; !ok {
			t.Errorf("30_grants.sql 이 목록 밖 역할 %q 에 GRANT 한다: %q", role, got[role])
		}
	}
	for role, w := range want {
		g := slices.Sorted(slices.Values(got[role]))
		w = slices.Sorted(slices.Values(w))
		if !slices.Equal(g, w) {
			t.Errorf("30_grants.sql 의 %s GRANT = %q, want %q (정본과 같은 집합)", role, g, w)
		}
	}
}

func firstStmt(s []string) string {
	if len(s) == 0 {
		return ""
	}
	return s[0]
}

func lastStmt(s []string) string {
	if len(s) == 0 {
		return ""
	}
	return s[len(s)-1]
}

// 메타 명령은 파일마다 허용 목록 안이고, 줄 단위로 놓인다 — 실적용 시험의 처리기가 줄 단위로 펼치므로
// 줄 가운데의 메타 명령은 psql 과 다르게 돈다.
func TestProdSQLMetaCommandsAreAllowListed(t *testing.T) {
	for _, name := range append(slices.Clone(prodSQLApplied), prodSchemaFile) {
		got := mustSplitPSQL(t, name).metas
		for _, m := range got {
			if !slices.Contains(prodSQLAllowedMeta[name], m) {
				t.Errorf("%s 의 메타 명령 %q 는 허용 목록 %q 밖이다", name, m, prodSQLAllowedMeta[name])
			}
		}
		if lines := metaLines(readProdSQL(t, name)); !slices.Equal(lines, got) {
			t.Errorf("%s 의 줄 머리 메타 명령 = %q, 문장 분석 = %q — 메타 명령은 한 줄을 통째로 쓴다", name, lines, got)
		}
	}
}

// metaLines 는 앞 공백을 뺀 첫 글자가 백슬래시인 줄이다(실적용 시험의 처리기가 보는 것).
func metaLines(src string) []string {
	var out []string
	sc := bufio.NewScanner(strings.NewReader(src))
	for sc.Scan() {
		if line := strings.TrimSpace(sc.Text()); strings.HasPrefix(line, `\`) {
			out = append(out, line)
		}
	}
	return out
}

// 00 ~ 40(과 20 이 읽는 사본)에는 트랜잭션 문이 없다 — 원자성은 psql -1 이 준다. 50 만 스스로
// BEGIN … ROLLBACK 이고 그 사이에 다른 트랜잭션 문이 없다(확인만 하고 되돌린다 — K7).
func TestProdSQLTransactionStatementConvention(t *testing.T) {
	for _, name := range append(slices.Clone(prodSQLApplied[:5]), prodSchemaFile) {
		for _, s := range mustSplitPSQL(t, name).statements {
			if slices.Contains(txKeywords, firstKeyword(s)) {
				t.Errorf("%s 에 트랜잭션 문 %q 이 있다 — psql -1 이 감싼다", name, normalizeSQL(s))
			}
		}
	}
	stmts := mustSplitPSQL(t, "50_advisory_probe.sql").statements
	if firstStmt(stmts) != "BEGIN" || lastStmt(stmts) != "ROLLBACK" {
		t.Fatalf("50_advisory_probe.sql 의 처음 · 끝 문장 = %q · %q, want BEGIN · ROLLBACK", firstStmt(stmts), lastStmt(stmts))
	}
	for _, s := range stmts[1 : len(stmts)-1] {
		if slices.Contains(txKeywords, firstKeyword(s)) {
			t.Errorf("50_advisory_probe.sql 가운데에 트랜잭션 문 %q 이 있다", normalizeSQL(s))
		}
	}
}

// splitPSQL 이 따옴표 · 달러 따옴표 · 주석 안의 ; · BEGIN · 백슬래시를 문장 경계로 보지 않는다 — 위 규약 시험이
// DO 블록 본문을 트랜잭션 문으로 잘못 읽지 않는다는 근거다.
func TestSplitPSQLKeepsQuotedBodiesWhole(t *testing.T) {
	src := "\\set ON_ERROR_STOP on\n" +
		"DO $do$\nBEGIN\n  PERFORM 1; -- ; \\x\nEND $do$;\n" +
		"SELECT 'a;b''c\\d', \"x;y\" /* ; */ FROM t;\n" +
		"SELECT $1::text;\n"
	got, err := splitPSQL(src)
	if err != nil {
		t.Fatalf("splitPSQL 오류 = %v", err)
	}
	want := psqlScript{
		statements: []string{
			"DO $do$\nBEGIN\n  PERFORM 1; -- ; \\x\nEND $do$",
			"SELECT 'a;b''c\\d', \"x;y\"  FROM t",
			"SELECT $1::text",
		},
		metas: []string{`\set ON_ERROR_STOP on`},
	}
	if !slices.Equal(got.statements, want.statements) || !slices.Equal(got.metas, want.metas) {
		t.Errorf("splitPSQL = %q · %q, want %q · %q", got.statements, got.metas, want.statements, want.metas)
	}
	for _, bad := range []string{"SELECT 'a", "DO $x$ BEGIN", "/* a"} {
		if _, err := splitPSQL(bad); err == nil {
			t.Errorf("splitPSQL(%q) 오류 = nil, want 닫히지 않음", bad)
		}
	}
}
