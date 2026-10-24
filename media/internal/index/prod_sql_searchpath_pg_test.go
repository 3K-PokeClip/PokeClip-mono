package index

// prod SQL 의 search_path 가로채기 음성 대조(CVE-2018-1058 계열 · kty 결정 2026-10-24).
//
// 10_roles.sql 은 앱 사용자 넷에게서 public 스키마 CREATE 를 거두고 자기 스키마를 준다(1단-나). 그래도 누군가
// public CREATE 를 다시 연 DB 에서, 그 신분이 public 에 pg_catalog 함수 · 연산자와 이름이 같고 인자 형이 호출 쪽과
// 정확히 맞는 판을 심으면 마스터 신분으로 도는 00–50 의 맨 이름 호출이 그 판을 고른다(심층 방어). 여기서는 그런
// 덫을 두 묶음 심고 00–50 을 마스터 대역으로 돌린 뒤 덫이 0회 불렸는지 본다.
//
//   - pgtest.PlantSearchPathTraps — 사이드카 · janitor 와 같은 덫 일곱(public CREATE 만 받은 가짜 앱 사용자)
//   - prodSQLTraps — 00–50 에만 나오는 꼴의 덫(public CREATE 만 받은 시험 전용 역할 trapPlanterPrefix…)
//
// 00 은 로컬에서 늘 실패하고(rds.force_ssl) 50 은 ROLLBACK 으로 끝나므로, 덫이 기록 표에 남긴 행은 그 트랜잭션과
// 함께 사라진다. 그래서 기록 표(RequireNotCalled)와 함께 마스터 연결이 받은 NOTICE 도 본다 — 두 묶음의 덫은 모두
// 불릴 때 trapNoticeMarker 로 시작하는 NOTICE 를 낸다.

import (
	"context"
	"maps"
	"os"
	"slices"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// trapNoticeMarker 는 덫이 불릴 때 내는 NOTICE 의 머리다(pgtest 의 덫 본문과 같은 문구).
const trapNoticeMarker = "search_path trap "

// trapPlanterPrefix 는 prodSQLTraps 를 심는 시험 전용 역할 이름의 앞부분이다 — 실행마다 난수 접미가 붙는다.
const trapPlanterPrefix = "pc_prodsql_planter_"

// prodSQLTrap 은 덫 하나다. name 은 NOTICE 에 싣는 이름이고 create 는 심는 문장들이다(시험 DB 와 함께 지워진다).
type prodSQLTrap struct {
	name   string
	create []string
}

// trapNotice 는 덫 본문의 머리 — 불렸음을 NOTICE 로 알린다.
func trapNotice(name string) string {
	return "RAISE NOTICE '" + trapNoticeMarker + "% called', '" + name + "';"
}

// prodTrapFunction 은 pg_catalog 함수 이름 그대로인 덫이다. result 는 원래 결과를 pg_catalog 로 만드는 문장이다.
func prodTrapFunction(signature, returns, result string) prodSQLTrap {
	return prodSQLTrap{
		name: signature,
		create: []string{"CREATE FUNCTION public." + signature + " RETURNS " + returns +
			" LANGUAGE plpgsql AS $fn$ BEGIN " + trapNotice(signature) + " " + result + " END $fn$"},
	}
}

// prodTrapOperator 는 pg_catalog 연산자 기호 그대로인 덫이다. fn 은 연산자를 받치는 덫 함수 이름이다.
func prodTrapOperator(op, left, right, returns, fn, result string) prodSQLTrap {
	name := op + "(" + left + ", " + right + ")"
	return prodSQLTrap{
		name: name,
		create: []string{
			"CREATE FUNCTION public." + fn + "(" + left + ", " + right + ") RETURNS " + returns +
				" LANGUAGE plpgsql AS $fn$ BEGIN " + trapNotice(name) + " " + result + " END $fn$",
			"CREATE OPERATOR public." + op + " (LEFTARG = " + left + ", RIGHTARG = " + right +
				", FUNCTION = public." + fn + ")",
		},
	}
}

// prodSQLTraps 는 00–50 의 고치기 전 꼴이 실제로 고른 덫이다(PG 17 실측 2026-10-24). 마스터 SQL 은 계정 이름을
// text 로 넘기므로 pg_catalog 의 (name, …) 판보다 (text, …) 덫이 정확히 맞고, 배열 연산 · unnest · format ·
// cardinality 는 pg_catalog 에 다형 판뿐이라 형을 박은 덫이 먼저 골린다.
var prodSQLTraps = []prodSQLTrap{
	prodTrapFunction("has_schema_privilege(text, text, text)", "boolean",
		"RETURN pg_catalog.has_schema_privilege($1::name, $2, $3);"),
	prodTrapFunction("has_table_privilege(text, text, text)", "boolean",
		"RETURN pg_catalog.has_table_privilege($1::name, $2, $3);"),
	prodTrapFunction("has_column_privilege(text, text, text, text)", "boolean",
		"RETURN pg_catalog.has_column_privilege($1::name, $2, $3, $4);"),
	prodTrapFunction("has_database_privilege(text, name, text)", "boolean",
		"RETURN pg_catalog.has_database_privilege($1::name, $2::text, $3);"),
	prodTrapFunction("has_function_privilege(text, text, text)", "boolean",
		"RETURN pg_catalog.has_function_privilege($1::name, $2, $3);"),
	prodTrapFunction("pg_has_role(text, text, text)", "boolean",
		"RETURN pg_catalog.pg_has_role($1::name, $2::name, $3);"),
	prodTrapFunction("unnest(boolean[])", "SETOF boolean", "RETURN QUERY SELECT pg_catalog.unnest($1);"),
	prodTrapFunction("cardinality(text[])", "integer", "RETURN pg_catalog.cardinality($1);"),
	prodTrapFunction("array_to_string(text[], text)", "text", "RETURN pg_catalog.array_to_string($1, $2);"),
	prodTrapFunction("format(text, text, text, text, text)", "text",
		"RETURN pg_catalog.format($1, $2, $3, $4, $5);"),
	prodTrapFunction("format(text, text, text, text, text, text)", "text",
		"RETURN pg_catalog.format($1, $2, $3, $4, $5, $6);"),
	// 집계는 행이 0개여도 마감 함수가 돈다 — 40 의 실패 단언 모음(평소 0행)도 덫을 부른다.
	{
		name: "array_agg(text)",
		create: []string{
			"CREATE FUNCTION public.pc_trap_array_agg_final(text[]) RETURNS text[] LANGUAGE plpgsql AS $fn$ BEGIN " +
				trapNotice("array_agg(text)") + " RETURN $1; END $fn$",
			"CREATE AGGREGATE public.array_agg(text) (SFUNC = pg_catalog.array_append, STYPE = text[], " +
				"FINALFUNC = public.pc_trap_array_agg_final)",
		},
	},
	prodTrapOperator("||", "text[]", "text", "text[]", "pc_trap_text_array_append",
		"RETURN pg_catalog.array_append($1, $2);"),
	prodTrapOperator("=", "text[]", "text[]", "boolean", "pc_trap_text_array_eq",
		"RETURN $1 OPERATOR(pg_catalog.=) $2;"),
	prodTrapOperator("=", "oid", "regprocedure", "boolean", "pc_trap_oid_eq_regprocedure",
		"RETURN $1 OPERATOR(pg_catalog.=) $2::oid;"),
	// 정수 상수는 integer 다 — oid 열과 비교하면 pg_catalog 에는 oid = oid 판뿐이다.
	prodTrapOperator("=", "oid", "integer", "boolean", "pc_trap_oid_eq_int",
		"RETURN $1 OPERATOR(pg_catalog.=) $2::oid;"),
}

// plantProdSQLTraps 는 superuser 연결에서 public CREATE 만 받은 시험 전용 역할을 만들고, 그 역할로 바꿔 prodSQLTraps
// 를 심는다. 바꾸기는 SET ROLE 이라 새 접속이 아니므로 CONNECT 는 주지 않는다(주면 DB 권한이 남아 역할을 지우지
// 못한다). 역할은 시험 DB 안에 덫을 소유하므로 DROP ROLE 만으로는 지워지지 않는다 — 만든 직후 같은 연결에서
// DROP OWNED BY → DROP ROLE 을 정리로 건다(closeConns 보다 늦게 등록되므로 먼저 돈다).
func (e *prodSQLEnv) plantProdSQLTraps(t *testing.T) {
	t.Helper()
	planter := trapPlanterPrefix + prodSQLRandHex(t, 4)
	if _, err := execScript(t, e.super, "CREATE ROLE "+planter+" NOLOGIN"); err != nil {
		t.Fatalf("덫 심는 역할 만들기 실패: %v", err)
	}
	t.Cleanup(func() {
		for _, s := range []string{"DROP OWNED BY " + planter, "DROP ROLE " + planter} {
			if _, err := e.super.Exec(context.Background(), s); err != nil {
				t.Errorf("덫 심는 역할 정리 실패(%s): %v", s, err)
			}
		}
	})
	stmts := []string{"GRANT CREATE ON SCHEMA public TO " + planter, "SET ROLE " + planter}
	for _, tr := range prodSQLTraps {
		stmts = append(stmts, tr.create...)
	}
	for _, s := range append(stmts, "RESET ROLE") {
		if _, err := execScript(t, e.super, s); err != nil {
			t.Fatalf("덫 심기 실패(%.80s): %v", s, err)
		}
	}
}

// superPool 은 시험 DB 에 superuser 로 붙은 풀이다 — pgtest.PlantSearchPathTraps 가 풀을 받는다. 풀은 이 호출
// 뒤에 등록한 정리(덫 · 가짜 앱 사용자)가 다 돈 다음에 닫힌다.
func (e *prodSQLEnv) superPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	cfg, err := pgxpool.ParseConfig(os.Getenv("PG_DSN"))
	if err != nil {
		t.Fatalf("DSN 파싱 실패: %v", err)
	}
	cfg.ConnConfig.Database = e.dbName
	pool, err := pgxpool.NewWithConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("시험 DB 풀 생성 실패(%s): %v", e.dbName, err)
	}
	t.Cleanup(pool.Close)
	return pool
}

// trapCalls 는 notices 가운데 덫이 낸 NOTICE 를 덫 이름별로 센 것이다.
func trapCalls(notices []*pgconn.Notice) map[string]int {
	calls := map[string]int{}
	for _, n := range notices {
		if name, ok := strings.CutPrefix(n.Message, trapNoticeMarker); ok {
			calls[strings.TrimSuffix(name, " called")]++
		}
	}
	return calls
}

// 음성 대조 — 앱 사용자가 public 에 덫을 심어도 마스터 대역이 돌리는 00–50 은 덫을 하나도 부르지 않는다.
// 00 은 로컬에서 rds.force_ssl(· tls)로 실패하고 50 은 회수 불가 갈래로 끝나지만(사례 ① · ⑥), 덫은 그 전에 불린다.
func TestProdSQLDoesNotCallSearchPathTraps(t *testing.T) {
	env := newProdSQLEnv(t)
	if _, err := applyProdSQL(t, env.master, expandProdSQL(t, "10_roles.sql")); err != nil {
		t.Fatalf("10_roles.sql 적용 오류 = %v, want nil", err)
	}
	traps := pgtest.PlantSearchPathTraps(t, env.superPool(t))
	env.plantProdSQLTraps(t)
	env.notices = nil

	_, err := applyProdSQL(t, env.master, expandProdSQL(t, "00_preflight.sql"))
	failedChecks(t, err, preflightFailedPrefix)
	env.mustApplyAll(t)
	if _, err := execScript(t, env.master, expandProdSQL(t, "50_advisory_probe.sql")); err != nil &&
		!isSQLState(err, pgInsufficientPrivilege) {
		t.Errorf("50_advisory_probe.sql(대역) 오류 = %v, want nil 또는 42501", err)
	}

	traps.RequireNotCalled(t)
	calls := trapCalls(env.notices)
	for _, name := range slices.Sorted(maps.Keys(calls)) {
		t.Errorf("가로채기 덫 %s 가 마스터 대역 신분으로 %d번 불렸다 — 00–50 의 맨 이름 호출이 public 의 판을 골랐다",
			name, calls[name])
	}
}

// 양성 — 10 뒤 앱 사용자는 public 에 덫을 심지 못한다(public CREATE 를 거뒀다).
func TestProdSQLAppUserCannotPlantTrapInPublic(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	stmt := prodSQLTraps[0].create[0]
	if _, err := execScript(t, env.super, "BEGIN; SET ROLE app_auth; "+stmt+"; ROLLBACK;"); !isSQLState(err, pgInsufficientPrivilege) {
		t.Errorf("app_auth 의 public 덫 심기 오류 = %v, want 42501", err)
	}
}
