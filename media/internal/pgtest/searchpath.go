package pgtest

// search_path 가로채기(CVE-2018-1058 계열) 덫 — 운영 DB 의 앱 사용자 넷을 흉내 낸다.
//
// 운영 RDS 에서 3번 서버 넷의 앱 사용자는 Flyway 때문에 public 스키마 CREATE 를 가진다(prod/runtime
// 10_roles.sql). 그 사용자가 public 에 pg_catalog 함수 · 연산자와 이름이 같고 인자 형이 호출 쪽 형과 **정확히**
// 맞는 판을 만들면, 스키마를 한정하지 않은 호출은 암묵 형 변환이 필요한 pg_catalog 판 대신 그 판을 고른다. 그 판은
// 부른 세션의 신분(media_indexer · media_janitor · media_owner)으로 돈다.
//
// PlantSearchPathTraps 는 그런 판(덫)을 public CREATE 만 받은 가짜 앱 사용자로 심는다. 덫은 원래 결과를 그대로
// 돌려주므로 시험 흐름은 멈추지 않고, 불릴 때마다 부른 신분을 기록 표에 남긴다(부작용). 시험은 끝에서
// RequireNotCalled 로 기록이 0 행인가를 본다. 덫 목록은 PG 17 에서 맨 이름 호출이 실제로 덫을 고른 꼴(2026-10-24
// 실측)에, 호출 쪽 인자가 varchar 일 때만 고르는 hashtext 판을 더한 것이다.

import (
	"context"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// appRolePrefix 는 가짜 앱 사용자 이름의 앞부분이다 — 실행마다 난수 접미가 붙는다.
const appRolePrefix = "pc_role_app_"

// trapLogTable 은 덫이 불린 기록이다. caller 의 기본값 current_user 는 덫을 부른 세션의 신분이다.
const trapLogTable = "public.pc_search_path_trap_log"

// searchPathTrap 은 덫 하나다. name 은 기록 · 실패 문구의 이름이고, create 는 심는 문장, drop 은 지우는 문장이다
// (남은 덫을 심기 전에 지울 때 쓴다).
type searchPathTrap struct {
	name   string
	create []string
	drop   []string
}

// trapBody 는 덫 함수 본문이다 — 기록을 남기고 NOTICE 를 낸 뒤 pg_catalog 로 원래 결과를 만든다.
func trapBody(name, result string) string {
	return "$fn$ BEGIN INSERT INTO " + trapLogTable + " (trap) VALUES ('" + name + "'); " +
		"RAISE NOTICE 'search_path trap % called', '" + name + "'; " + result + " END $fn$"
}

// trapFunction 은 pg_catalog 함수 이름 그대로인 덫이다.
func trapFunction(signature, returns, result string) searchPathTrap {
	return searchPathTrap{
		name:   signature,
		create: []string{"CREATE FUNCTION public." + signature + " RETURNS " + returns + " LANGUAGE plpgsql AS " + trapBody(signature, result)},
		drop:   []string{"DROP FUNCTION IF EXISTS public." + signature},
	}
}

// trapOperator 는 pg_catalog 연산자 기호 그대로인 덫이다. fn 은 연산자를 받치는 덫 함수 이름이다.
func trapOperator(op, left, right, returns, fn, result string) searchPathTrap {
	name := op + "(" + left + ", " + right + ")"
	args := "(" + left + ", " + right + ")"
	return searchPathTrap{
		name: name,
		create: []string{
			"CREATE FUNCTION public." + fn + args + " RETURNS " + returns + " LANGUAGE plpgsql AS " + trapBody(name, result),
			"CREATE OPERATOR public." + op + " (LEFTARG = " + left + ", RIGHTARG = " + right + ", FUNCTION = public." + fn + ")",
		},
		drop: []string{
			"DROP OPERATOR IF EXISTS public." + op + " " + args,
			"DROP FUNCTION IF EXISTS public." + fn + args,
		},
	}
}

// searchPathTraps 는 심는 덫 전부다.
var searchPathTraps = []searchPathTrap{
	// hashtext 는 integer 를 내는데 pg_catalog 에는 bigint 판만 있다 — 컷오프 직렬화 잠금(store.go).
	trapFunction("pg_advisory_xact_lock(integer)", "void", "PERFORM pg_catalog.pg_advisory_xact_lock($1::bigint);"),
	trapFunction("hashtext(varchar)", "integer", "RETURN pg_catalog.hashtext($1::text);"),
	trapFunction("unnest(text[])", "SETOF text", "RETURN QUERY SELECT pg_catalog.unnest($1);"),
	trapFunction("unnest(bigint[])", "SETOF bigint", "RETURN QUERY SELECT pg_catalog.unnest($1);"),
	// pg_catalog 에는 integer * interval 이 없다(double precision 판뿐) — 조각 끝 시각 duration_ms * interval.
	trapOperator("*", "integer", "interval", "interval", "pc_trap_int_mul_interval",
		"RETURN $1::double precision OPERATOR(pg_catalog.*) $2;"),
	// 카탈로그 확인 DO 블록(ddl.go) — oid 열 = regclass, information_schema 의 sql_identifier 열 = 문자열.
	trapOperator("=", "oid", "regclass", "boolean", "pc_trap_oid_eq_regclass",
		"RETURN $1 OPERATOR(pg_catalog.=) $2::oid;"),
	trapOperator("=", "information_schema.sql_identifier", "information_schema.sql_identifier", "boolean",
		"pc_trap_sqlid_eq",
		"RETURN $1::name OPERATOR(pg_catalog.=) $2::name;"),
}

// SearchPathTraps 는 이 실행이 심은 덫과 그 덫을 심은 가짜 앱 사용자다.
type SearchPathTraps struct {
	role  string // public CREATE 만 받은 가짜 앱 사용자 이름(난수 접미)
	admin *pgxpool.Pool
	conn  *pgx.Conn // 가짜 앱 사용자 연결 — 기록을 읽고 덫을 지운다
}

// PlantSearchPathTraps 는 admin 이 붙은 DB 에 public CREATE 만 가진 가짜 앱 사용자를 만들고, 그 사용자로 덫과 기록
// 표를 심는다. 덫 · 기록 표 · 사용자는 t.Cleanup 이 지운다. 앞선 실행이 강제 종료돼 남긴 덫은 심기 전에 admin 으로
// 지운다(admin 이 그 덫을 지울 수 있어야 한다 — 로컬 compose 의 소유자 계정은 초사용자다).
func PlantSearchPathTraps(t *testing.T, admin *pgxpool.Pool) *SearchPathTraps {
	t.Helper()
	ctx := context.Background()
	for _, s := range trapDropSQL() {
		if _, err := admin.Exec(ctx, s); err != nil {
			t.Fatalf("남은 덫 정리 실패(%s): %v", s, err)
		}
	}
	name := appRolePrefix + randHex(t, 4)
	password := createLoginRole(t, admin, name)
	t.Cleanup(func() { dropRole(t, admin, name, nil) })
	if _, err := admin.Exec(ctx, "GRANT CREATE ON SCHEMA public TO "+name); err != nil {
		t.Fatalf("가짜 앱 사용자 public CREATE 부여 실패: %v", err)
	}

	cfg := admin.Config().ConnConfig
	cfg.User, cfg.Password = name, password
	conn, err := pgx.ConnectConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("가짜 앱 사용자 연결 실패: %v", err)
	}
	traps := &SearchPathTraps{role: name, admin: admin, conn: conn}
	t.Cleanup(func() { traps.remove(t) }) // dropRole 보다 늦게 등록 — 먼저 돌아 덫을 지운 뒤 사용자를 지운다
	for _, s := range trapCreateSQL() {
		if _, err := conn.Exec(ctx, s); err != nil {
			t.Fatalf("덫 심기 실패(%.80s): %v", s, err)
		}
	}
	return traps
}

// trapCreateSQL 은 기록 표와 덫을 심는 문장들이다. 덫은 부른 신분으로 돌므로 기록 표 INSERT 를 PUBLIC 에 연다.
func trapCreateSQL() []string {
	out := []string{
		"CREATE TABLE " + trapLogTable + " (trap text NOT NULL, caller name NOT NULL DEFAULT current_user)",
		"GRANT INSERT ON " + trapLogTable + " TO PUBLIC",
	}
	for _, tr := range searchPathTraps {
		out = append(out, tr.create...)
	}
	return out
}

// trapDropSQL 은 덫과 기록 표를 지우는 문장들이다(없으면 아무것도 하지 않는다).
func trapDropSQL() []string {
	var out []string
	for _, tr := range searchPathTraps {
		out = append(out, tr.drop...)
	}
	return append(out, "DROP TABLE IF EXISTS "+trapLogTable)
}

// calls 는 덫 이름 → 불린 횟수다(불린 덫만).
func (s *SearchPathTraps) calls(t *testing.T) map[string]int {
	t.Helper()
	calls := map[string]int{}
	for _, c := range s.callers(t) {
		calls[c.trap] += c.count
	}
	return calls
}

// RequireNotCalled 는 덫이 한 번도 불리지 않았는가를 본다 — 불렸으면 덫마다 부른 신분과 횟수를 싣는다.
func (s *SearchPathTraps) RequireNotCalled(t *testing.T) {
	t.Helper()
	for _, c := range s.callers(t) {
		t.Errorf("가로채기 덫 %s 가 %s 신분으로 %d번 불렸다 — 맨 이름 호출이 public 의 판을 골랐다", c.trap, c.caller, c.count)
	}
}

// trapCall 은 기록 표의 (덫, 부른 신분) 묶음 하나다.
type trapCall struct {
	trap, caller string
	count        int
}

func (s *SearchPathTraps) callers(t *testing.T) []trapCall {
	t.Helper()
	rows, err := s.conn.Query(context.Background(),
		"SELECT trap, caller::text, count(*)::int FROM "+trapLogTable+" GROUP BY 1, 2 ORDER BY 1, 2")
	if err != nil {
		t.Fatalf("덫 기록 읽기 실패: %v", err)
	}
	out, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (trapCall, error) {
		var c trapCall
		err := r.Scan(&c.trap, &c.caller, &c.count)
		return c, err
	})
	if err != nil {
		t.Fatalf("덫 기록 읽기 실패: %v", err)
	}
	return out
}

// remove 는 가짜 앱 사용자가 가진 것(덫 · 기록 표)을 그 사용자 자신으로 지우고 연결을 닫은 뒤, admin 이 준
// public CREATE 를 거둔다(받은 쪽은 남이 준 권한을 거둘 수 없다).
func (s *SearchPathTraps) remove(t *testing.T) {
	t.Helper()
	ctx := context.Background()
	if _, err := s.conn.Exec(ctx, "DROP OWNED BY CURRENT_USER"); err != nil {
		t.Errorf("덫 정리 실패(%s): %v", s.role, err)
	}
	if err := s.conn.Close(ctx); err != nil {
		t.Errorf("가짜 앱 사용자 연결 닫기 실패: %v", err)
	}
	if _, err := s.admin.Exec(ctx, "REVOKE CREATE ON SCHEMA public FROM "+s.role); err != nil {
		t.Errorf("가짜 앱 사용자 public CREATE 회수 실패: %v", err)
	}
}
