package index

// prod SQL 실적용 시험(POK-304 · 착수 체크리스트 H2 · H3 · H5 · 계획 r1 처분 P1 · P2 · P3 · P13 · P14).
//
// 운영 적용 신분은 RDS 마스터 사용자다 — superuser 가 아니고 CREATEROLE 을 가진다. superuser 로 돌리면
// SET ROLE · 소유자 GRANT 의 권한 실패를 못 잡으므로(H5), 여기서는 LOGIN CREATEROLE NOSUPERUSER 대역을
// 만들고 그 대역이 소유한 새 DB 에 00 ~ 50 을 실제로 돌린다. 대역은 RDS 처럼 rds_iam 의 ADMIN 을 받고,
// DB 소유자이므로 public 스키마의 소유자(pg_database_owner)를 겸한다.
//
// 이 처리기는 psql 이 아니다: 허용한 메타 명령 둘만 펼치고(\set ON_ERROR_STOP on 은 지우고
// \ir stream_schema.sql 은 끼워 넣는다) 파일마다 BEGIN … COMMIT 으로 감싸 한 번에 보낸다(psql -1 대역).
// 대역도 rds_superuser 가 아니다 — 첫 운영 적용이 최종 판정이다.
//
// 고정 역할 media_owner · media_indexer · media_janitor · app_* 넷은 SQL 이 만드는 실제 이름이고 역할은 클러스터
// 전역이다. 그래서 사례마다 새로 만들고 지우며, 시작할 때 이미 있으면 멈춘다(남의 역할을 지우지 않게) —
// 같은 PG 에서 이 시험을 동시에 돌리지 않는다.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"slices"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// prodFixedRoles 는 10_roles.sql 이 만드는 역할이다(media 셋 + 3번 서버의 앱 사용자 넷).
var prodFixedRoles = append([]string{"media_owner", "media_indexer", "media_janitor"}, appDBUsers...)

// rdsIAMRole 은 RDS 가 미리 만들어 두는 IAM 인증 역할이다. 로컬 · CI PG 에는 없어서 없으면 시험이 만든다.
const rdsIAMRole = "rds_iam"

// 00 · 40 이 실패한 점검의 식별자를 싣는 오류 문구 머리다(뒤에 ", " 로 이은 식별자).
const (
	preflightFailedPrefix = "preflight failed: "
	verifyFailedPrefix    = "verify failed: "
)

// verifyOK 는 40_verify.sql 이 모든 단언을 통과했을 때 내는 결과 행이다.
const verifyOK = "VERIFY OK"

// selfGrantLine 은 10_roles.sql 에서 마스터가 media_owner 로 SET ROLE 할 수 있게 하는 줄이다(H5 대조가 뺀다).
const selfGrantLine = "GRANT media_owner TO CURRENT_USER WITH SET TRUE, INHERIT FALSE;"

// advisoryVerifyID 는 40 이 media_indexer 의 pg_advisory_xact_lock(bigint) EXECUTE 를 보는 단언의 식별자다(H3 · P3).
const advisoryVerifyID = "advisory_xact_lock_media_indexer"

// 50_advisory_probe.sql 을 회수 권한이 없는 신분으로 돌렸을 때 PG 가 내는 경고 문구다.
const noPrivilegesRevoked = "no privileges could be revoked"

// prodSQLEnv 는 사례 하나의 격리된 적용 환경이다(P1 — 사례마다 새 대역 · 새 DB · 새 고정 역할).
type prodSQLEnv struct {
	// master 는 대역 마스터로 시험 DB 에 붙은 연결이다 — 00 ~ 50 을 돌리는 신분이다.
	master *pgx.Conn
	// super 는 같은 DB 에 superuser 로 붙은 연결이다 — ⑦ · P3 대조에만 쓴다.
	super *pgx.Conn
	// masterName 은 대역 마스터 역할 이름이다.
	masterName string
	// dbName 은 이 사례가 만든 시험 DB 이름이다.
	dbName string
	// notices 는 master 연결이 받은 NOTICE · WARNING 이다.
	notices []*pgconn.Notice
}

// newProdSQLEnv 는 PG_DSN(superuser 관리 접속)으로 대역 마스터와 그 대역이 소유한 새 DB 를 만들고 두 연결을
// 돌려준다. 정리는 만든 것마다 만든 직후에 등록한다 — 난수 이름이 이미 있는 역할 · DB 와 겹쳐 생성이 실패하면
// 그 남의 것을 지우지 않는다(검수 c7 r1 이연 M). t.Cleanup 은 거꾸로 돌므로 정리 순서는 연결 닫기 → DB DROP
// (WITH FORCE) → 고정 역할 → 대역 → rds_iam(이 시험이 만든 경우에만)이다.
func newProdSQLEnv(t *testing.T) *prodSQLEnv {
	t.Helper()
	dsn := os.Getenv("PG_DSN")
	if dsn == "" {
		t.Skip("PG_DSN 미설정 — DB 통합 테스트를 건너뛴다")
	}
	ctx := context.Background()
	admin, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatalf("관리 접속 실패: %v", err)
	}
	t.Cleanup(func() { admin.Close(ctx) }) // 맨 먼저 등록 — 아래 정리가 다 돈 뒤에 닫힌다
	requireSuperuser(t, admin)
	requireNoFixedRoles(t, admin)

	suffix := prodSQLRandHex(t, 4)
	masterName, dbName := "pc_prodsql_master_"+suffix, "pc_prodsql_"+suffix
	env := &prodSQLEnv{masterName: masterName, dbName: dbName}

	var hasIAM bool
	if err := admin.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $1)`, rdsIAMRole).Scan(&hasIAM); err != nil {
		t.Fatalf("rds_iam 조회 실패: %v", err)
	}
	if !hasIAM {
		mustAdminExec(t, admin, "CREATE ROLE "+rdsIAMRole+" NOLOGIN")
		cleanupAdminExec(t, admin, "DROP ROLE IF EXISTS "+rdsIAMRole)
	}
	password := prodSQLRandHex(t, 16) // 16진 문자만 — 따옴표를 깰 글자가 없다
	var validUntil string
	if err := admin.QueryRow(ctx, `SELECT (now() + interval '1 hour')::text`).Scan(&validUntil); err != nil {
		t.Fatalf("대역 만료 시각 계산 실패: %v", err)
	}
	mustAdminExec(t, admin, "CREATE ROLE "+masterName+" LOGIN CREATEROLE NOSUPERUSER PASSWORD '"+password+"' VALID UNTIL '"+validUntil+"'")
	cleanupAdminExec(t, admin, "DROP ROLE IF EXISTS "+masterName)
	// 고정 역할은 시작할 때 없음을 확인했으므로 이 사례의 SQL 이 만든 것이다.
	cleanupAdminExec(t, admin, "DROP ROLE IF EXISTS "+strings.Join(prodFixedRoles, ", "))
	mustAdminExec(t, admin, "GRANT "+rdsIAMRole+" TO "+masterName+" WITH ADMIN OPTION")
	mustAdminExec(t, admin, "CREATE DATABASE "+dbName+" OWNER "+masterName)
	cleanupAdminExec(t, admin, "DROP DATABASE IF EXISTS "+dbName+" WITH (FORCE)")

	t.Cleanup(env.closeConns)
	env.master = connectProdSQL(t, dsn, dbName, masterName, password, func(_ *pgconn.PgConn, n *pgconn.Notice) {
		env.notices = append(env.notices, n)
	})
	env.super = connectProdSQL(t, dsn, dbName, "", "", nil)
	requireStandInMaster(t, env.master)
	return env
}

// requireSuperuser 는 관리 접속이 superuser 인지 본다 — 대역 · DB 생성과 ⑦ · P3 대조에 필요하다.
func requireSuperuser(t *testing.T, admin *pgx.Conn) {
	t.Helper()
	var super bool
	if err := admin.QueryRow(context.Background(), `SELECT rolsuper FROM pg_roles WHERE rolname = current_user`).Scan(&super); err != nil {
		t.Fatalf("관리 접속 신분 조회 실패: %v", err)
	}
	if !super {
		t.Fatal("PG_DSN 의 역할이 superuser 가 아니다 — 대역 마스터 · 시험 DB 를 만들 수 없다")
	}
}

// requireNoFixedRoles 는 고정 역할이 하나도 없는지 본다. 있으면 다른 실행이 돌고 있거나 지난 실행의 잔여다 —
// 지우지 않고 멈춘다.
func requireNoFixedRoles(t *testing.T, admin *pgx.Conn) {
	t.Helper()
	var left []string
	if err := admin.QueryRow(context.Background(),
		`SELECT coalesce(array_agg(rolname ORDER BY rolname), '{}') FROM pg_roles WHERE rolname = ANY($1)`, prodFixedRoles).Scan(&left); err != nil {
		t.Fatalf("고정 역할 조회 실패: %v", err)
	}
	if len(left) > 0 {
		t.Fatalf("고정 역할 %v 가 이미 있다 — 같은 PG 에서 이 시험이 돌고 있거나 지난 실행의 잔여다(손으로 확인 후 지운다)", left)
	}
}

// requireStandInMaster 는 대역이 RDS 마스터 모양(비superuser · CREATEROLE · DB 소유자)인지 본다(H5).
func requireStandInMaster(t *testing.T, master *pgx.Conn) {
	t.Helper()
	var super, createRole, owner bool
	err := master.QueryRow(context.Background(), `
SELECT r.rolsuper, r.rolcreaterole, pg_get_userbyid(d.datdba) = current_user
  FROM pg_roles r, pg_database d
 WHERE r.rolname = current_user AND d.datname = current_database()`).Scan(&super, &createRole, &owner)
	if err != nil {
		t.Fatalf("대역 신분 조회 실패: %v", err)
	}
	if super || !createRole || !owner {
		t.Fatalf("대역 (superuser, CREATEROLE, DB 소유자) = (%v, %v, %v), want (false, true, true)", super, createRole, owner)
	}
}

// connectProdSQL 은 PG_DSN 의 서버 · TLS 설정으로 DB db 에 붙는다. user 가 비면 PG_DSN 의 역할 그대로다.
func connectProdSQL(t *testing.T, dsn, db, user, password string, onNotice pgconn.NoticeHandler) *pgx.Conn {
	t.Helper()
	cfg, err := pgx.ParseConfig(dsn)
	if err != nil {
		t.Fatalf("DSN 파싱 실패: %v", err)
	}
	cfg.Database = db
	if user != "" {
		cfg.User, cfg.Password = user, password
	}
	cfg.OnNotice = onNotice
	conn, err := pgx.ConnectConfig(context.Background(), cfg)
	if err != nil {
		t.Fatalf("시험 DB 접속 실패(%s): %v", db, err)
	}
	return conn
}

// closeConns 는 열린 시험 DB 연결을 닫는다 — DB DROP 보다 먼저 돈다.
func (e *prodSQLEnv) closeConns() {
	ctx := context.Background()
	for _, c := range []*pgx.Conn{e.master, e.super} {
		if c != nil {
			c.Close(ctx)
		}
	}
}

// cleanupAdminExec 는 사례가 끝날 때 admin 으로 sql 을 돌리도록 등록한다. 실패는 오류로 남긴다(대역은 1시간 뒤
// 만료된다).
func cleanupAdminExec(t *testing.T, admin *pgx.Conn, sql string) {
	t.Helper()
	t.Cleanup(func() {
		if _, err := admin.Exec(context.Background(), sql); err != nil {
			t.Errorf("시험 환경 정리 실패(%s): %v", sql, err)
		}
	})
}

func mustAdminExec(t *testing.T, admin *pgx.Conn, sql string) {
	t.Helper()
	if _, err := admin.Exec(context.Background(), sql); err != nil {
		t.Fatalf("시험 환경 준비 실패(%.60s…): %v", sql, err)
	}
}

// prodSQLRandHex 는 n 바이트 난수의 16진 문자열이다.
func prodSQLRandHex(t *testing.T, n int) string {
	t.Helper()
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		t.Fatalf("난수 생성 실패: %v", err)
	}
	return hex.EncodeToString(buf)
}

// expandProdSQL 은 파일 name 의 메타 명령을 펼친 SQL 이다. 허용 목록 밖 메타 명령이면 멈춘다. 줄 수는 바꾸지
// 않는다(\set 줄은 빈 줄로 남긴다) — PG 오류의 줄 위치가 파일과 어긋나지 않게.
func expandProdSQL(t *testing.T, name string) string {
	t.Helper()
	lines := strings.Split(readProdSQL(t, name), "\n")
	for i, line := range lines {
		meta := strings.TrimSpace(line)
		if !strings.HasPrefix(meta, `\`) {
			continue
		}
		if !slices.Contains(prodSQLAllowedMeta[name], meta) {
			t.Fatalf("%s 의 메타 명령 %q 는 허용 목록 밖이다", name, meta)
		}
		switch meta {
		case metaStopOnError:
			lines[i] = ""
		case metaIncludeDDL:
			lines[i] = expandProdSQL(t, prodSchemaFile)
		}
	}
	return strings.Join(lines, "\n")
}

// removeLines 는 body 에서 앞뒤 공백을 뺀 내용이 drop 중 하나인 줄을 뺀 것과 뺀 줄 수다(음성 대조용).
func removeLines(body string, drop ...string) (string, int) {
	var kept []string
	n := 0
	for _, line := range strings.Split(body, "\n") {
		if slices.Contains(drop, strings.TrimSpace(line)) {
			n++
			continue
		}
		kept = append(kept, line)
	}
	return strings.Join(kept, "\n"), n
}

// execScript 는 sql 을 단순 질의 하나로 보내 결과를 모은다. 오류면 ROLLBACK 하고 연결이 idle 로 돌아왔는지
// 본 뒤 오류를 돌려준다(P2 — 의도한 오류가 다음 단계의 연결 상태를 더럽히지 않게).
func execScript(t *testing.T, conn *pgx.Conn, sql string) ([]*pgconn.Result, error) {
	t.Helper()
	ctx := context.Background()
	results, err := conn.PgConn().Exec(ctx, sql).ReadAll()
	if err != nil {
		if _, rbErr := conn.PgConn().Exec(ctx, "ROLLBACK").ReadAll(); rbErr != nil {
			t.Fatalf("오류(%v) 뒤 ROLLBACK 실패: %v", err, rbErr)
		}
	}
	if s := conn.PgConn().TxStatus(); s != 'I' {
		t.Fatalf("연결 트랜잭션 상태 = %q, want 'I'(idle)", s)
	}
	return results, err
}

// applyProdSQL 은 펼친 body 를 psql -1 처럼 한 트랜잭션으로 보낸다.
func applyProdSQL(t *testing.T, conn *pgx.Conn, body string) ([]*pgconn.Result, error) {
	t.Helper()
	return execScript(t, conn, "BEGIN;\n"+body+"\nCOMMIT;")
}

// mustApplyAll 은 10 ~ 40 을 대역으로 순서대로 적용하고 40 이 VERIFY OK 를 냈는지 본다. 00 은 CI 가 TLS ·
// rds.force_ssl 을 못 맞춰 실패하므로 여기서 돌리지 않는다(사례 ① 이 따로 잰다).
func (e *prodSQLEnv) mustApplyAll(t *testing.T) {
	t.Helper()
	for _, name := range prodSQLApplied[1:4] {
		if _, err := applyProdSQL(t, e.master, expandProdSQL(t, name)); err != nil {
			t.Fatalf("%s 적용 오류 = %v, want nil", name, err)
		}
	}
	e.mustVerify(t)
}

// mustVerify 는 40_verify.sql 을 대역으로 돌려 VERIFY OK 를 냈는지 본다.
func (e *prodSQLEnv) mustVerify(t *testing.T) {
	t.Helper()
	results, err := applyProdSQL(t, e.master, expandProdSQL(t, "40_verify.sql"))
	if err != nil {
		t.Fatalf("40_verify.sql 오류 = %v, want nil", err)
	}
	if !hasRow(results, verifyOK) {
		t.Fatalf("40_verify.sql 결과에 %q 행이 없다", verifyOK)
	}
}

// hasRow 는 결과 가운데 첫 열이 want 인 행이 있는가다.
func hasRow(results []*pgconn.Result, want string) bool {
	for _, r := range results {
		for _, row := range r.Rows {
			if len(row) > 0 && string(row[0]) == want {
				return true
			}
		}
	}
	return false
}

// failedChecks 는 00 · 40 의 RAISE 오류에서 실패한 점검 식별자를 정렬해 꺼낸다. 그 모양의 오류가 아니면 멈춘다.
func failedChecks(t *testing.T, err error, prefix string) []string {
	t.Helper()
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) || pgErr.Code != "P0001" || !strings.HasPrefix(pgErr.Message, prefix) {
		t.Fatalf("오류 = %v, want P0001 %q…", err, prefix)
	}
	ids := strings.Split(strings.TrimPrefix(pgErr.Message, prefix), ", ")
	slices.Sort(ids)
	return ids
}

// ① 00 은 실패한 점검을 모아 한 번에 알린다. CI · 로컬은 RDS 가 아니라 rds.force_ssl 이 없고, 연결이 TLS 가
// 아니면 tls 도 실패한다 — 그 밖의 점검(판 · rds_iam · 비superuser + CREATEROLE · DB 소유자 · public CREATE)은
// 대역에서 통과해야 한다.
func TestProdSQLPreflightFailsOnlyOnTLSAndForceSSL(t *testing.T) {
	env := newProdSQLEnv(t)
	var tls bool
	if err := env.master.QueryRow(context.Background(), `SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()`).Scan(&tls); err != nil {
		t.Fatalf("연결 TLS 조회 실패: %v", err)
	}
	want := []string{"rds.force_ssl"}
	if !tls {
		want = append(want, "tls")
	}
	slices.Sort(want)

	_, err := applyProdSQL(t, env.master, expandProdSQL(t, "00_preflight.sql"))
	if got := failedChecks(t, err, preflightFailedPrefix); !slices.Equal(got, want) {
		t.Errorf("00_preflight.sql 실패 점검 = %q, want %q", got, want)
	}
}

// ② · ③ 10 ~ 40 을 비superuser 마스터 대역으로 적용하면 VERIFY OK 이고, 한 번 더 적용해도 VERIFY OK 다(수렴).
func TestProdSQLAppliesAsNonSuperuserMasterAndConverges(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	env.mustApplyAll(t)
}

// ④ 음성 대조(H2) — 30 의 SET ROLE media_owner · RESET ROLE 두 줄을 빼면 마스터는 INHERIT FALSE 라
// media_owner 소유 표에 GRANT 하지 못한다(42501).
func TestProdSQLGrantsWithoutSetRoleAreDenied(t *testing.T) {
	env := newProdSQLEnv(t)
	for _, name := range []string{"10_roles.sql", "20_schema.sql"} {
		if _, err := applyProdSQL(t, env.master, expandProdSQL(t, name)); err != nil {
			t.Fatalf("%s 적용 오류 = %v, want nil", name, err)
		}
	}
	body, n := removeLines(expandProdSQL(t, "30_grants.sql"), grantsSetRole+";", grantsResetRole+";")
	if n != 2 {
		t.Fatalf("30_grants.sql 에서 뺀 감싸기 줄 = %d, want 2", n)
	}
	if _, err := applyProdSQL(t, env.master, body); !isSQLState(err, pgInsufficientPrivilege) {
		t.Errorf("감싸기 없는 30_grants.sql 오류 = %v, want 42501", err)
	}
}

// ⑤ 음성 대조(H5) — 10 에서 자기 부여 줄을 빼면 CREATEROLE 생성자는 ADMIN 만 받고 SET 은 받지 못해
// (PG16+ createrole_self_grant 기본값 = 빈 문자열) 20 의 SET ROLE media_owner 가 42501 이다. 대조 전에 같은 상태에서
// SET ROLE 이 42501 인지 먼저 본다(P13 — 역할이 없어서 나는 42704 와 가른다).
func TestProdSQLSetRoleWithoutSelfGrantIsDenied(t *testing.T) {
	env := newProdSQLEnv(t)
	body, n := removeLines(expandProdSQL(t, "10_roles.sql"), selfGrantLine)
	if n != 1 {
		t.Fatalf("10_roles.sql 에서 뺀 자기 부여 줄 = %d, want 1", n)
	}
	if _, err := applyProdSQL(t, env.master, body); err != nil {
		t.Fatalf("자기 부여 없는 10_roles.sql 적용 오류 = %v, want nil", err)
	}
	if _, err := execScript(t, env.master, "SET ROLE media_owner"); !isSQLState(err, pgInsufficientPrivilege) {
		t.Fatalf("자기 부여 없이 SET ROLE media_owner 오류 = %v, want 42501 — CREATEROLE 생성자가 SET 을 자동으로 받는다면 H5 전제가 틀렸다", err)
	}
	if _, err := applyProdSQL(t, env.master, expandProdSQL(t, "20_schema.sql")); !isSQLState(err, pgInsufficientPrivilege) {
		t.Errorf("자기 부여 없이 20_schema.sql 오류 = %v, want 42501", err)
	}
}

// ⑥ 50 을 대역(회수 권한 없음)으로 돌리면 WARNING(회수 안 됨) 또는 42501 이고, 뒤이은 40 은 VERIFY OK 다
// (H3 — 40 → 50 → 40).
func TestProdSQLAdvisoryProbeAsMasterLeavesStateUnchanged(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	env.notices = nil
	_, err := execScript(t, env.master, expandProdSQL(t, "50_advisory_probe.sql"))
	switch {
	case isSQLState(err, pgInsufficientPrivilege):
		t.Logf("50_advisory_probe.sql(대역) 갈래 = 42501 오류(회수 불가): %v", err)
	case err == nil && hasNotice(env.notices, noPrivilegesRevoked):
		t.Logf("50_advisory_probe.sql(대역) 갈래 = WARNING %q(회수 불가)", noPrivilegesRevoked)
	default:
		t.Errorf("50_advisory_probe.sql(대역) = (오류 %v, 알림 %v), want 42501 또는 WARNING %q", err, noticeMessages(env.notices), noPrivilegesRevoked)
	}
	env.mustVerify(t)
}

// ⑦ 50 을 superuser 로 돌리면 트랜잭션 안에서 PUBLIC 의 EXECUTE 가 실제로 빠지고(회수 가능 갈래), ROLLBACK
// 으로 되돌아가 뒤이은 40 은 VERIFY OK 다.
func TestProdSQLAdvisoryProbeAsSuperuserRollsBack(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	results, err := execScript(t, env.super, expandProdSQL(t, "50_advisory_probe.sql"))
	if err != nil {
		t.Fatalf("50_advisory_probe.sql(superuser) 오류 = %v, want nil", err)
	}
	if got := probeRow(t, results, "after"); got[1] != "f" {
		t.Errorf("50_advisory_probe.sql(superuser) 회수 뒤 PUBLIC EXECUTE = %q, want f", got[1])
	}
	env.mustVerify(t)
}

// verifyFailuresAfter 는 superuser 가 한 트랜잭션에서 tamper 로 상태를 어긋나게 한 뒤 대역 신분으로 돌아가
// 40 을 돌렸을 때 실패한 단언 식별자다(P14). 끝에서 ROLLBACK 하므로 상태는 그대로다 — 뒤이은 40 이 VERIFY OK 인지
// 함께 본다.
func (e *prodSQLEnv) verifyFailuresAfter(t *testing.T, tamper string) []string {
	t.Helper()
	script := "BEGIN;\n" + tamper + ";\nSET ROLE " + e.masterName + ";\n" +
		expandProdSQL(t, "40_verify.sql") + "\nROLLBACK;"
	_, err := execScript(t, e.super, script)
	got := failedChecks(t, err, verifyFailedPrefix)
	e.mustVerify(t)
	return got
}

// P3 음성 대조 — PUBLIC 에서 회수만 하고 media_indexer 에 다시 주지 않으면 40 이 바로 그 단언 하나로
// 실패한다(P14 — 식별자로 확인). 같은 트랜잭션에서 ROLLBACK 해 상태는 그대로다.
func TestProdSQLVerifyCatchesAdvisoryRevokeWithoutRegrant(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	got := env.verifyFailuresAfter(t, "REVOKE EXECUTE ON FUNCTION pg_advisory_xact_lock(bigint) FROM PUBLIC")
	if !slices.Equal(got, []string{advisoryVerifyID}) {
		t.Errorf("회수 뒤 40_verify.sql 실패 단언 = %q, want [%s]", got, advisoryVerifyID)
	}
}

// 음성 대조 — 계정이 media_owner 의 멤버가 되면 SET ROLE media_owner 로 표를 지울 수 있다. INHERIT FALSE 면
// 표 권한 행렬(has_table_privilege)에는 아무것도 드러나지 않으므로 40 이 그 멤버십 단언 하나로 실패해야 한다
// (검수 c7 r1 HIGH). 기본값(INHERIT TRUE) 멤버십은 이 단언과 권한 행렬이 함께 잡는다.
func TestProdSQLVerifyCatchesExtraMembership(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	const tamper = "GRANT media_owner TO media_indexer WITH INHERIT FALSE, SET TRUE"
	got := env.verifyFailuresAfter(t, tamper)
	if want := []string{"membership_media_indexer"}; !slices.Equal(got, want) {
		t.Errorf("%s 뒤 40_verify.sql 실패 단언 = %q, want %q", tamper, got, want)
	}
}

// 음성 대조 — public 스키마 CREATE 를 PUBLIC 이 아닌 계정에 직접 주면 40 이 그 단언 하나로 실패한다
// (검수 c7 r1 HIGH).
func TestProdSQLVerifyCatchesDirectSchemaCreate(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	got := env.verifyFailuresAfter(t, "GRANT CREATE ON SCHEMA public TO media_janitor")
	if want := []string{"schema_create_media_janitor"}; !slices.Equal(got, want) {
		t.Errorf("GRANT CREATE ON SCHEMA public TO media_janitor 뒤 40_verify.sql 실패 단언 = %q, want %q", got, want)
	}
}

// 음성 대조 — 표 단위 행렬 밖의 권한 어긋남(검수 c7 r1 이연 M)도 40 이 바로 그 단언으로만 잡는다(CONNECT 회수는
// 계정마다 하나). 사례끼리 상태를 나누지 않는다: verifyFailuresAfter 가 매번 ROLLBACK 하고 VERIFY OK 로
// 되돌아왔는지 본다.
func TestProdSQLVerifyCatchesPrivilegeDrift(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	third := "pc_prodsql_third_" + prodSQLRandHex(t, 4)
	tests := []struct {
		name   string
		tamper string
		want   []string
	}{
		{
			name:   "column grant on another table",
			tamper: "GRANT UPDATE (state) ON stream_sessions TO media_janitor",
			want:   []string{"column:media_janitor:stream_sessions.state:UPDATE"},
		},
		{
			name:   "PG17 MAINTAIN",
			tamper: "GRANT MAINTAIN ON stream_segments TO media_indexer",
			want:   []string{"privilege:media_indexer:stream_segments:MAINTAIN"},
		},
		{
			name:   "grant option",
			tamper: "GRANT SELECT ON stream_cutoffs TO media_janitor WITH GRANT OPTION",
			want:   []string{"privilege:media_janitor:stream_cutoffs:SELECT+grant_option"},
		},
		{
			name:   "column grant option",
			tamper: "GRANT UPDATE (local_path) ON stream_segments TO media_janitor WITH GRANT OPTION",
			want:   []string{"column:media_janitor:stream_segments.local_path:UPDATE+grant_option"},
		},
		{
			name:   "CONNECT revoked from PUBLIC",
			tamper: "REVOKE CONNECT ON DATABASE " + env.dbName + " FROM PUBLIC",
			want: []string{
				"connect:app_auth", "connect:app_chat_collector", "connect:app_chat_detector", "connect:app_clip",
				"connect:media_indexer", "connect:media_janitor",
			},
		},
		{
			// 계정의 public 스키마 USAGE 는 PUBLIC 기본이다 — 회수되면 표 권한이 있어도 이름을 풀지 못한다.
			name:   "schema USAGE revoked from PUBLIC",
			tamper: "REVOKE USAGE ON SCHEMA public FROM PUBLIC",
			want: []string{
				"schema_usage_app_auth", "schema_usage_app_chat_collector", "schema_usage_app_chat_detector",
				"schema_usage_app_clip", "schema_usage_media_indexer", "schema_usage_media_janitor",
			},
		},
		{
			name:   "app cross grant beyond the list",
			tamper: "GRANT UPDATE ON stream_segments TO app_clip",
			want:   []string{"privilege:app_clip:stream_segments:UPDATE"},
		},
		{
			name:   "app cross grant on another table",
			tamper: "GRANT SELECT ON stream_sessions TO app_chat_collector",
			want:   []string{"privilege:app_chat_collector:stream_sessions:SELECT"},
		},
		{
			name:   "app without cross grants reads stream table",
			tamper: "GRANT SELECT ON stream_cutoffs TO app_auth",
			want:   []string{"privilege:app_auth:stream_cutoffs:SELECT"},
		},
		{
			name:   "app column grant",
			tamper: "GRANT UPDATE (local_path) ON stream_segments TO app_clip",
			want:   []string{"column:app_clip:stream_segments.local_path:UPDATE"},
		},
		{
			name:   "app attribute",
			tamper: "ALTER ROLE app_chat_detector CREATEDB",
			want:   []string{"role:app_chat_detector"},
		},
		{
			name:   "app membership",
			tamper: "GRANT media_owner TO app_clip WITH INHERIT FALSE, SET TRUE",
			want:   []string{"membership_app_clip"},
		},
		{
			name:   "app schema CREATE revoked",
			tamper: "REVOKE CREATE ON SCHEMA public FROM app_auth",
			want:   []string{"schema_create_app_auth"},
		},
		{
			// 멤버십은 부여자(대역 마스터)별로 남는다 — superuser 의 맨 REVOKE 는 그 행을 지우지 않는다(PG16+).
			name:   "app rds_iam revoked",
			tamper: "REVOKE rds_iam FROM app_chat_collector GRANTED BY " + env.masterName,
			want:   []string{"membership_app_chat_collector", "rds_iam:app_chat_collector"},
		},
		{
			name:   "third member of media_owner",
			tamper: "CREATE ROLE " + third + " NOLOGIN; GRANT media_owner TO " + third,
			want:   []string{"members_media_owner"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := env.verifyFailuresAfter(t, tt.tamper); !slices.Equal(got, tt.want) {
				t.Errorf("%s 뒤 40_verify.sql 실패 단언 = %q, want %q", tt.tamper, got, tt.want)
			}
		})
	}
}

// 음성 대조(M6-0) — 장부 스키마와 M6 권한이 어긋나면 40 이 바로 그 단언으로만 잡는다. 트리거를 지운 DB 가
// VERIFY OK 로 지나가면 하한 없이 이미지가 배포된다(seq 재사용 — 불변식 4).
func TestProdSQLVerifyCatchesLedgerSchemaDrift(t *testing.T) {
	env := newProdSQLEnv(t)
	env.mustApplyAll(t)
	tests := []struct {
		name   string
		tamper string
		want   []string
	}{
		{
			name:   "floor guard trigger dropped",
			tamper: "DROP TRIGGER stream_segments_seq_floor_guard ON stream_segments",
			want:   []string{"trigger:stream_segments_seq_floor_guard"},
		},
		{
			name:   "floor raise trigger dropped",
			tamper: "DROP TRIGGER stream_segments_seq_floor_raise ON stream_segments",
			want:   []string{"trigger:stream_segments_seq_floor_raise"},
		},
		{
			name:   "floor raise function not security definer",
			tamper: "ALTER FUNCTION stream_segments_raise_seq_floor() SECURITY INVOKER",
			want:   []string{"function:stream_segments_raise_seq_floor"},
		},
		{
			name:   "floor guard function search_path reset",
			tamper: "ALTER FUNCTION stream_segments_check_seq_floor() RESET search_path",
			want:   []string{"function:stream_segments_check_seq_floor"},
		},
		{
			name:   "session column dropped",
			tamper: "ALTER TABLE stream_sessions DROP COLUMN stop_marked_at",
			want:   []string{"ledger_column:stream_sessions.stop_marked_at"},
		},
		{
			name:   "round open index dropped",
			tamper: "DROP INDEX stream_sessions_round_open_idx",
			want:   []string{"index:stream_sessions_round_open_idx"},
		},
		{
			name:   "segment session fk index dropped",
			tamper: "DROP INDEX stream_segments_session_id_idx",
			want:   []string{"index:stream_segments_session_id_idx"},
		},
		{
			name:   "gap session fk index dropped",
			tamper: "DROP INDEX stream_published_gaps_session_idx",
			want:   []string{"index:stream_published_gaps_session_idx"},
		},
		{
			name:   "inherits fk index dropped",
			tamper: "DROP INDEX stream_sessions_inherits_idx",
			want:   []string{"index:stream_sessions_inherits_idx"},
		},
		{
			name:   "M6 expiry privilege revoked",
			tamper: "REVOKE DELETE ON stream_segments FROM media_indexer",
			want:   []string{"privilege:media_indexer:stream_segments:DELETE"},
		},
		{
			name:   "floor table write granted",
			tamper: "GRANT UPDATE ON stream_seq_floors TO media_indexer",
			want:   []string{"privilege:media_indexer:stream_seq_floors:UPDATE"},
		},
		{
			name:   "outbox privilege revoked",
			tamper: "REVOKE INSERT ON broadcast_lifecycle_outbox FROM media_indexer",
			want:   []string{"privilege:media_indexer:broadcast_lifecycle_outbox:INSERT"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := env.verifyFailuresAfter(t, tt.tamper); !slices.Equal(got, tt.want) {
				t.Errorf("%s 뒤 40_verify.sql 실패 단언 = %q, want %q", tt.tamper, got, tt.want)
			}
		})
	}
}

// probeRow 는 50 의 결과에서 첫 열이 phase 인 행(phase, PUBLIC EXECUTE, media_indexer EXECUTE)이다.
func probeRow(t *testing.T, results []*pgconn.Result, phase string) [3]string {
	t.Helper()
	for _, r := range results {
		for _, row := range r.Rows {
			if len(row) == 3 && string(row[0]) == phase {
				return [3]string{string(row[0]), string(row[1]), string(row[2])}
			}
		}
	}
	t.Fatalf("50_advisory_probe.sql 결과에 %q 행이 없다", phase)
	return [3]string{}
}

func hasNotice(notices []*pgconn.Notice, substr string) bool {
	return slices.ContainsFunc(notices, func(n *pgconn.Notice) bool {
		return n.Severity == "WARNING" && strings.Contains(n.Message, substr)
	})
}

func noticeMessages(notices []*pgconn.Notice) []string {
	out := make([]string, 0, len(notices))
	for _, n := range notices {
		out = append(out, n.Severity+": "+n.Message)
	}
	return out
}
