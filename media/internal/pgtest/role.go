package pgtest

// 사이드카 DB 계정 media_indexer 의 최소 권한 목록과, 그 권한만 가진 시험 역할(POK-311).
//
// 운영 사이드카는 RDS 에 media_indexer 로 붙는다. 로컬 compose · CI 는 소유자 계정으로 붙어 이 경계를 재지
// 못하므로, SQL 이 사는 패키지(index · session · rewind/publish)의 역할 시험이 여기서 같은 권한만 가진 역할을
// 만들어 사이드카 저장 경로를 실제로 돌린다. 목록은 이 파일 한 곳이고 README 의 GRANT 블록과 같다는 것을
// role_test.go 가 단언한다 — 목록 = 시험 = README.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// MediaIndexerRole 은 운영 사이드카 DB 계정 이름이다 — README GRANT 블록의 대상이다.
const MediaIndexerRole = "media_indexer"

// mediaIndexerRolePrefix 는 시험 역할 이름의 앞부분이다 — 실행마다 난수 접미가 붙는다(역할은 클러스터 전역이라
// 겹친 실행 · 다른 패키지가 서로의 역할을 지우지 않게).
const mediaIndexerRolePrefix = "pc_role_media_indexer_"

// tableGrant 는 표 하나에 주는 권한이다. 단위는 표다(kty 결정 Q3 — 열 단위면 열 추가마다 운영 GRANT 가 깨진다).
type tableGrant struct {
	table      string
	privileges []string
}

// mediaIndexerGrants 는 사이드카(segment-indexer · 업로더 · 세션 · 되감기 발행자)의 비시험 SQL 전수에서 뽑은
// 권한이다. 근거(문장 → 표 · 동작)는 각 패키지의 media_indexer_role_pg_test.go 머리 표에 있다. 시퀀스는 없고
// (DDL 에 SERIAL · IDENTITY 0), 트리거 함수 · advisory 잠금 · set_config 는 PUBLIC 기본으로 충분하며, DDL 은
// 주지 않는다(ENSURE_SCHEMA=false — 스키마는 별도 역할이 적용한다).
//
// M6 몫은 선부여다(ADR-0006 개정 2026-10-26). segments · sessions · gaps DELETE 와 cutoffs UPDATE · DELETE 는
// M6-4(60일 만료)가, outbox 의 네 권한은 M6-8(계약9 발행기)과 M6-4(만료가 그 회차 행을 지움)가 쓰고, 쓰는 문장은
// 그 PR 이 들인다. 지금 쓰는 M6 권한은 stream_seq_floors SELECT 하나다 — LoadCursor 와 하한 검사 트리거가 읽는다.
var mediaIndexerGrants = []tableGrant{
	{table: "stream_segments", privileges: []string{"SELECT", "INSERT", "UPDATE", "DELETE"}},
	{table: "stream_sessions", privileges: []string{"SELECT", "INSERT", "UPDATE", "DELETE"}},
	{table: "stream_cutoffs", privileges: []string{"SELECT", "INSERT", "UPDATE", "DELETE"}},
	{table: "stream_published_gaps", privileges: []string{"SELECT", "INSERT", "UPDATE", "DELETE"}},
	{table: "stream_seq_floors", privileges: []string{"SELECT"}},
	{table: "broadcast_lifecycle_outbox", privileges: []string{"SELECT", "INSERT", "UPDATE", "DELETE"}},
}

// MediaIndexerGrantSQL 은 role 에게 media_indexer 권한을 주는 GRANT 문장들이다(끝 ; 없음).
func MediaIndexerGrantSQL(role string) []string {
	out := make([]string, 0, len(mediaIndexerGrants))
	for _, g := range mediaIndexerGrants {
		out = append(out, "GRANT "+strings.Join(g.privileges, ", ")+" ON "+g.table+" TO "+role)
	}
	return out
}

// pgInsufficientPrivilege 는 권한 부족 SQLSTATE 다.
const pgInsufficientPrivilege = "42501"

// IsInsufficientPrivilege 는 err 가 권한 부족(42501)인가다.
func IsInsufficientPrivilege(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == pgInsufficientPrivilege
}

// Role 은 이 실행 전용 시험 역할과 그 역할로 붙은 풀이다. 풀이 보낸 문장의 결과(성공 · 42501)를 기록한다.
type Role struct {
	// Name 은 시험 역할 이름이다(난수 접미).
	Name string
	// Pool 은 그 역할로 붙은 풀이다 — 시험 대상 코드에 이 풀을 준다.
	Pool *pgxpool.Pool

	admin *pgxpool.Pool
	sent  *statementLog
}

// NewMediaIndexerRole 은 admin 이 붙은 DB 에 media_indexer 권한만 가진 시험 역할을 만들고 그 역할의 풀을 돌려준다.
// 역할은 t.Cleanup 이 권한을 거두고 지운다(이 실행이 만든 역할만). 지우지 못하고 남아도 1시간 뒤 만료된다.
//
// 비밀번호는 실행마다 새 난수이고 이 역할에만 쓴다. CREATE ROLE 문장은 서버 문장 로그에 비밀번호째 남을 수
// 있으니 문장 로그를 켠 공유 클러스터에서는 돌리지 않는다(janitor 역할 시험과 같은 전제).
func NewMediaIndexerRole(t *testing.T, admin *pgxpool.Pool) *Role {
	t.Helper()
	ctx := context.Background()
	name := mediaIndexerRolePrefix + randHex(t, 4)
	password := createLoginRole(t, admin, name)
	// 만든 직후에 등록한다 — 아래 GRANT 가 실패로 끝나도 역할이 클러스터에 남지 않는다.
	t.Cleanup(func() { dropRole(t, admin, name, grantedTables()) })
	for _, s := range MediaIndexerGrantSQL(name) {
		if _, err := admin.Exec(ctx, s); err != nil {
			t.Fatalf("시험 역할 권한 부여 실패(%s): %v", s, err)
		}
	}

	log := &statementLog{ok: map[string]int{}, denied: map[string]int{}}
	cfg := admin.Config()
	cfg.ConnConfig.User, cfg.ConnConfig.Password = name, password
	cfg.ConnConfig.Tracer = log
	if cfg.ConnConfig.RuntimeParams == nil {
		cfg.ConnConfig.RuntimeParams = map[string]string{}
	}
	cfg.ConnConfig.RuntimeParams["application_name"] = "role-media-indexer"
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("시험 역할 풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close) // dropRole 보다 늦게 등록 — 먼저 돌아 연결을 닫은 뒤 역할을 지운다
	return &Role{Name: name, Pool: pool, admin: admin, sent: log}
}

// loginRolePrefix 는 로그인 전용 시험 역할 이름의 앞부분이다 — 실행마다 난수 접미가 붙는다.
const loginRolePrefix = "pc_role_login_"

// LoginRole 은 이 실행 전용 로그인 역할이다. 표 권한은 없다(DB CONNECT 는 PUBLIC 기본값).
type LoginRole struct {
	// Name 은 역할 이름이다(난수 접미).
	Name string
	// Password 는 이 역할에만 쓰는 난수 비밀번호다 — 로그 · 오류에 나타나는지 찾는 누출 시험의 표지로 쓴다.
	Password string
}

// NewLoginRole 은 admin 이 붙은 클러스터에 권한 없는 로그인 역할을 만든다. 접속 자체만 필요한 시험(기동 ·
// 연결 확인 줄)이 소유자 계정 대신 이 역할로 붙는다. 역할은 t.Cleanup 이 지우고(이 실행이 만든 역할만), 지우지
// 못하고 남아도 1시간 뒤 만료된다. CREATE ROLE 전제는 NewMediaIndexerRole 과 같다.
func NewLoginRole(t *testing.T, admin *pgxpool.Pool) LoginRole {
	t.Helper()
	name := loginRolePrefix + randHex(t, 4)
	password := createLoginRole(t, admin, name)
	t.Cleanup(func() { dropRole(t, admin, name, nil) })
	return LoginRole{Name: name, Password: password}
}

// createLoginRole 은 역할 name 을 난수 비밀번호(돌려준다)로 만든다. 정리 등록은 호출자가 한다.
func createLoginRole(t *testing.T, admin *pgxpool.Pool, name string) string {
	t.Helper()
	ctx := context.Background()
	password := randHex(t, 16) // 16진 문자만 — 따옴표를 깰 글자가 없다
	// 만료 = 서버 시계로 1시간 뒤. 실행이 강제 종료돼 Cleanup 이 돌지 않아도 남은 역할은 그 뒤 로그인할 수 없다
	// (C4 보안 검수 L5). 값은 timestamptz 의 text 꼴(숫자 · - · : · . · + · 공백)이라 따옴표를 깰 글자가 없다.
	var validUntil string
	if err := admin.QueryRow(ctx, `SELECT (now() + interval '1 hour')::text`).Scan(&validUntil); err != nil {
		t.Fatalf("시험 역할 만료 시각 계산 실패: %v", err)
	}
	if _, err := admin.Exec(ctx, "CREATE ROLE "+name+" LOGIN PASSWORD '"+password+"' VALID UNTIL '"+validUntil+"'"); err != nil {
		t.Fatalf("시험 역할 생성 실패: %v", err)
	}
	return password
}

// Revoke 는 이 역할에서 표 table 의 권한 privilege 하나를 거둔다 — 음성 대조(그 권한이 정말 필요한가)용이다.
func (r *Role) Revoke(t *testing.T, privilege, table string) {
	t.Helper()
	sql, err := revokeSQL(privilege, table, r.Name)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := r.admin.Exec(context.Background(), sql); err != nil {
		t.Fatalf("권한 회수 실패(%s ON %s): %v", privilege, table, err)
	}
}

// revokeSQL 은 REVOKE 문장을 만든다. 인자를 문장에 붙여 넣으므로 이 목록이 그 표에 준 권한 하나(대문자 그대로)가
// 아니면 거부한다 — 목록 밖 표 · 권한 · SQL 조각이 관리자 풀로 나가지 않게(C4 보안 검수 L5).
func revokeSQL(privilege, table, role string) (string, error) {
	for _, g := range mediaIndexerGrants {
		if g.table == table && slices.Contains(g.privileges, privilege) {
			return "REVOKE " + privilege + " ON " + table + " FROM " + role, nil
		}
	}
	return "", fmt.Errorf("회수 대상 %q ON %q 는 media_indexer 목록이 준 권한이 아니다", privilege, table)
}

// RequireSent 는 want(이름 → 문장)의 문장마다 이 역할 풀이 한 번 이상 보내 성공했는가를 본다. 호출이 오류를
// 삼키는 갈래(진단 질의 등)도 여기서 드러난다.
func (r *Role) RequireSent(t *testing.T, want map[string]string) {
	t.Helper()
	names := make([]string, 0, len(want))
	for name := range want {
		names = append(names, name)
	}
	slices.Sort(names)
	for _, name := range names {
		if !r.sent.has(want[name]) {
			t.Errorf("%s 가 역할 %s 로 성공한 적이 없다(보내지 않았거나 실패했다)", name, r.Name)
		}
	}
}

// RequireDenied 는 문장 sql 이 이 역할 풀에서 42501 로 거부된 적이 있는가를 본다.
func (r *Role) RequireDenied(t *testing.T, name, sql string) {
	t.Helper()
	if !r.sent.wasDenied(sql) {
		t.Errorf("%s 가 역할 %s 에서 42501 로 거부된 적이 없다", name, r.Name)
	}
}

// RequireNoneDenied 는 이 역할 풀에서 42501 로 거부된 문장이 하나도 없었는가를 본다 — 양성 시험이 t.Cleanup 으로
// 건다. 오류를 값(중단 로그 · 결과 모름)으로 접는 경로가 실패해도 원인이 권한이라는 것이 실패 문구에 남는다.
func (r *Role) RequireNoneDenied(t *testing.T) {
	t.Helper()
	for _, sql := range r.sent.deniedStatements() {
		t.Errorf("역할 %s 에서 42501 로 거부된 문장: %.120s", r.Name, strings.Join(strings.Fields(sql), " "))
	}
}

// statementLog 는 풀이 보낸 문장의 결과를 문장 글자로 센다(pgx.QueryTracer). 여러 연결이 동시에 부른다.
type statementLog struct {
	mu     sync.Mutex
	ok     map[string]int
	denied map[string]int
}

// statementKey 는 문장 시작이 ctx 에 적어 끝에서 읽는 문장 글자다 — pgx 는 시작이 돌려준 ctx 로 끝을 부른다.
type statementKey struct{}

func (l *statementLog) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	return context.WithValue(ctx, statementKey{}, d.SQL)
}

func (l *statementLog) TraceQueryEnd(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryEndData) {
	sql, _ := ctx.Value(statementKey{}).(string)
	l.mu.Lock()
	defer l.mu.Unlock()
	switch {
	case d.Err == nil:
		l.ok[sql]++
	case IsInsufficientPrivilege(d.Err):
		l.denied[sql]++
	}
}

func (l *statementLog) has(sql string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.ok[sql] > 0
}

func (l *statementLog) wasDenied(sql string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.denied[sql] > 0
}

func (l *statementLog) deniedStatements() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]string, 0, len(l.denied))
	for sql := range l.denied {
		out = append(out, sql)
	}
	slices.Sort(out)
	return out
}

// randHex 는 n 바이트 난수의 16진 문자열이다.
func randHex(t *testing.T, n int) string {
	t.Helper()
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		t.Fatalf("난수 생성 실패: %v", err)
	}
	return hex.EncodeToString(buf)
}

// grantedTables 는 media_indexer 목록이 권한을 주는 표 이름들이다.
func grantedTables() []string {
	tables := make([]string, 0, len(mediaIndexerGrants))
	for _, g := range mediaIndexerGrants {
		tables = append(tables, g.table)
	}
	return tables
}

// dropRole 은 이 실행이 만든 역할 name 만 tables 의 권한을 거두고(비면 거두지 않는다) 지운다.
func dropRole(t *testing.T, admin *pgxpool.Pool, name string, tables []string) {
	t.Helper()
	ctx := context.Background()
	var exists bool
	if err := admin.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $1)`, name).Scan(&exists); err != nil {
		t.Errorf("시험 역할 조회 실패: %v", err)
		return
	}
	if !exists {
		return
	}
	stmts := []string{"DROP ROLE " + name}
	if len(tables) > 0 {
		stmts = append([]string{"REVOKE ALL ON " + strings.Join(tables, ", ") + " FROM " + name}, stmts...)
	}
	for _, s := range stmts {
		if _, err := admin.Exec(ctx, s); err != nil {
			t.Errorf("시험 역할 정리 실패(%s): %v", s, err)
		}
	}
}
