package index

// janitor 최소 권한 역할 시험(POK-371 · 이월 C8 — 보안 검수 M-2).
//
// 운영의 janitor 계정 media_janitor 에 줄 권한은 설계 6절이 정했다:
// SELECT ON stream_segments · stream_sessions · stream_cutoffs, UPDATE (local_path) ON stream_segments.
// 로컬 compose 는 소유자 계정으로 붙어 이 경계를 시험하지 못한다. 그래서 여기서 같은 권한만 가진
// 역할을 만들어 janitor 가 쓰는 장부 메서드 전부가 그 권한 안에서 도는지 실 PG 로 잰다.
// 역할 이름은 클러스터 전역이라 시험 전용 이름을 쓰고, 끝나면 권한을 거두고 지운다.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// lcRoleName 은 media_janitor 와 같은 권한을 받는 시험 역할이다.
const lcRoleName = "pc_lc_media_janitor"

// lcRoleGrants 는 설계 6절의 media_janitor 권한 그대로다. 운영 GRANT 와 이 목록이 갈라지면
// 이 시험이 지키는 경계가 운영과 달라진다 — 바꿀 때 설계 6절 · README 와 함께 고친다.
var lcRoleGrants = []string{
	"GRANT SELECT ON stream_segments, stream_sessions, stream_cutoffs TO " + lcRoleName,
	"GRANT UPDATE (local_path) ON stream_segments TO " + lcRoleName,
}

const pgInsufficientPrivilege = "42501"

// lcRolePool 은 시험 역할을 만들고 그 역할로 붙은 풀을 돌려준다. 역할은 Cleanup 이 지운다.
func lcRolePool(t *testing.T, admin *pgxpool.Pool) *pgxpool.Pool {
	t.Helper()
	ctx := context.Background()
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		t.Fatalf("역할 비밀번호 생성 실패: %v", err)
	}
	password := hex.EncodeToString(buf) // 16진 문자만 — 따옴표를 깰 글자가 없다
	lcDropRole(t, admin)                // 지난 실행이 죽어 남긴 역할
	stmts := append([]string{"CREATE ROLE " + lcRoleName + " LOGIN PASSWORD '" + password + "'"}, lcRoleGrants...)
	for _, s := range stmts {
		if _, err := admin.Exec(ctx, s); err != nil {
			t.Fatalf("역할 준비 실패(%.40s…): %v", s, err)
		}
	}
	t.Cleanup(func() { lcDropRole(t, admin) })

	cfg := admin.Config()
	cfg.ConnConfig.User, cfg.ConnConfig.Password = lcRoleName, password
	if cfg.ConnConfig.RuntimeParams == nil {
		cfg.ConnConfig.RuntimeParams = map[string]string{}
	}
	cfg.ConnConfig.RuntimeParams["application_name"] = "lc-role-janitor"
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("역할 풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close) // lcDropRole 보다 늦게 등록 — 먼저 돌아 연결을 닫은 뒤 역할을 지운다
	return pool
}

func lcDropRole(t *testing.T, admin *pgxpool.Pool) {
	t.Helper()
	ctx := context.Background()
	var exists bool
	if err := admin.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $1)`, lcRoleName).Scan(&exists); err != nil {
		t.Fatalf("역할 조회 실패: %v", err)
	}
	if !exists {
		return
	}
	for _, s := range []string{
		"REVOKE ALL ON stream_segments, stream_sessions, stream_cutoffs FROM " + lcRoleName,
		"DROP ROLE " + lcRoleName,
	} {
		if _, err := admin.Exec(ctx, s); err != nil {
			t.Errorf("역할 정리 실패(%s): %v", s, err)
		}
	}
}

func lcIsPrivilegeError(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == pgInsufficientPrivilege
}

// janitor 장부 메서드 다섯 + 기동 버전 조회가 media_janitor 권한만으로 성공한다.
func TestLocalCleanupRunsWithJanitorLeastPrivilegeRole(t *testing.T) {
	admin := newTestPool(t)
	lcPut(t, admin, lcRow{stream: "lc-role", seq: 0}, lcRow{stream: "lc-role", seq: 1})
	store := NewLocalCleanupStore(lcRolePool(t, admin))
	ctx := context.Background()

	if ready, err := store.SchemaReady(ctx); err != nil || !ready {
		t.Fatalf("SchemaReady = (%v, %v), want (true, nil)", ready, err)
	}
	segs, _, err := store.DeletableSegments(ctx, lcPolicy(), 10, LocalCursor{})
	if err != nil || len(segs) != 2 {
		t.Fatalf("DeletableSegments = (%d행, %v), want (2행, nil)", len(segs), err)
	}
	if _, err := store.InvalidCutoffs(ctx); err != nil {
		t.Fatalf("InvalidCutoffs 오류 = %v, want nil", err)
	}
	res, err := store.RemoveUnderRowLock(ctx, "lc-role", 0, lcPath("lc-role", 0), func() bool { return true })
	if err != nil || res.Outcome != RowLockCleared {
		t.Fatalf("RemoveUnderRowLock = (%+v, %v), want Cleared", res, err)
	}
	if ok, err := store.ClearLocalPath(ctx, "lc-role", 1, lcPath("lc-role", 1)); err != nil || !ok {
		t.Fatalf("ClearLocalPath = (%v, %v), want (true, nil)", ok, err)
	}
	// cmd/janitor queryServerVersion 과 같은 문장이다(기동 거부 판정 — E3′).
	var version string
	if err := store.pool.QueryRow(ctx, "SHOW server_version_num").Scan(&version); err != nil {
		t.Fatalf("SHOW server_version_num 오류 = %v, want nil", err)
	}
	for seq := int64(0); seq <= 1; seq++ {
		if p := lcLocalPath(t, admin, "lc-role", seq); p != nil {
			t.Errorf("seq %d local_path = %q, want NULL", seq, *p)
		}
	}
}

// 음성 대조 — 시험이 권한 경계를 실제로 재는지 본다. UPDATE (local_path) 를 거두면 선잠금
// (FOR UPDATE 는 UPDATE 권한이 있는 열이 하나는 있어야 한다)과 CAS 가 42501 이고 파일 단계는
// 돌지 않는다. 권한 밖 쓰기(DELETE · INSERT)는 처음부터 42501 이다.
func TestLocalCleanupRoleWithoutLocalPathUpdateIsDenied(t *testing.T) {
	admin := newTestPool(t)
	lcPut(t, admin, lcRow{stream: "lc-role-deny", seq: 0})
	pool := lcRolePool(t, admin)
	store := NewLocalCleanupStore(pool)
	ctx := context.Background()
	if _, err := admin.Exec(ctx, "REVOKE UPDATE (local_path) ON stream_segments FROM "+lcRoleName); err != nil {
		t.Fatalf("권한 회수 실패: %v", err)
	}

	acted := false
	res, err := store.RemoveUnderRowLock(ctx, "lc-role-deny", 0, lcPath("lc-role-deny", 0), func() bool { acted = true; return true })
	if !lcIsPrivilegeError(err) || res.Stage != StageLock || acted {
		t.Errorf("RemoveUnderRowLock = (%+v, %v, remove 호출 %v), want 42501 · StageLock · remove 0", res, err, acted)
	}
	if _, err := store.ClearLocalPath(ctx, "lc-role-deny", 0, lcPath("lc-role-deny", 0)); !lcIsPrivilegeError(err) {
		t.Errorf("ClearLocalPath 오류 = %v, want 42501", err)
	}
	for _, s := range []string{
		"DELETE FROM stream_segments WHERE stream_id = 'lc-role-deny'",
		"INSERT INTO stream_cutoffs (stream_id, cutoff_seq, seed_reason, seed_channel) VALUES ('lc-role-deny', 0, 'live_ingress', 'watcher')",
	} {
		if _, err := pool.Exec(ctx, s); !lcIsPrivilegeError(err) {
			t.Errorf("권한 밖 쓰기(%.30s…) 오류 = %v, want 42501", s, err)
		}
	}
	if p := lcLocalPath(t, admin, "lc-role-deny", 0); p == nil {
		t.Error("local_path = NULL, want 그대로 — 권한 없이 바뀌었다")
	}
}
