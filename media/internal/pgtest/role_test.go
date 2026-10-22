package pgtest

import (
	"context"
	"os"
	"slices"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

// readmePath 는 media/README.md 다 — 이 테스트는 media/internal/pgtest 에서 돈다.
const readmePath = "../../README.md"

// readmeGrantHeading 은 README 에서 media_indexer GRANT 목록을 싣는 절의 제목이다.
const readmeGrantHeading = "## 사이드카 DB 계정 media_indexer 권한"

// readmeGrantLines 는 README 의 그 절에서 첫 sql 코드 블록의 줄을 앞뒤 공백과 끝 ; 를 떼어 돌려준다.
func readmeGrantLines(t *testing.T) []string {
	t.Helper()
	raw, err := os.ReadFile(readmePath)
	if err != nil {
		t.Fatalf("README 읽기 실패: %v", err)
	}
	_, section, ok := strings.Cut(string(raw), "\n"+readmeGrantHeading+"\n")
	if !ok {
		t.Fatalf("README 에 %q 절이 없다", readmeGrantHeading)
	}
	_, block, ok := strings.Cut(section, "```sql\n")
	if !ok {
		t.Fatalf("README %q 절에 sql 코드 블록이 없다", readmeGrantHeading)
	}
	block, _, ok = strings.Cut(block, "```")
	if !ok {
		t.Fatalf("README %q 절의 sql 코드 블록이 닫히지 않았다", readmeGrantHeading)
	}
	var lines []string
	for _, line := range strings.Split(block, "\n") {
		if line = strings.TrimSpace(line); line != "" {
			lines = append(lines, strings.TrimSuffix(line, ";"))
		}
	}
	return lines
}

// README 의 GRANT 목록과 역할 시험이 주는 목록은 한 목록이어야 한다 — 갈라지면 시험이 잰 권한과 운영에
// 주는 권한이 달라져, 시험이 초록이어도 운영 사이드카가 42501 로 멈추거나 시험이 재지 않은 권한이 나간다.
func TestMediaIndexerGrantsMatchREADME(t *testing.T) {
	want := MediaIndexerGrantSQL(MediaIndexerRole)

	got := readmeGrantLines(t)

	if !slices.Equal(got, want) {
		t.Fatalf("README GRANT 목록 =\n%s\nwant(pgtest 목록)\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}

// 권한은 표 단위 SELECT · INSERT · UPDATE 안에서만 준다(kty 결정 Q3 — DELETE 없음). 사이드카는 행을 지우지
// 않고(지우는 쪽은 M6 만료 · 장애 절차) DDL 도 내지 않는다(ENSURE_SCHEMA=false).
func TestMediaIndexerGrantsStayWithinSelectInsertUpdate(t *testing.T) {
	allowed := []string{"SELECT", "INSERT", "UPDATE"}
	for _, g := range mediaIndexerGrants {
		for _, p := range g.privileges {
			if !slices.Contains(allowed, p) {
				t.Errorf("%s 의 권한 %q 는 허용 목록 %v 밖이다", g.table, p, allowed)
			}
		}
	}
}

// roleTables 는 GRANT 대상 표의 빈 껍데기를 만든다 — 이 패키지는 스키마를 모르므로 이름만 같으면 된다.
func roleTables(ctx context.Context, pool *pgxpool.Pool) error {
	for _, g := range mediaIndexerGrants {
		if _, err := pool.Exec(ctx, "CREATE TABLE IF NOT EXISTS "+g.table+" (x int)"); err != nil {
			return err
		}
	}
	return nil
}

func noReset(context.Context, *pgxpool.Pool) error { return nil }

// 역할은 시험이 끝나면 권한을 거두고 지워진다 — 클러스터 전역 객체라 남으면 다음 실행 · 다른 패키지에
// 쌓인다. 역할 풀은 목록대로 SELECT 가 되고 권한 밖(DELETE)은 42501 이다. 성공한 문장만 기록에 남는다.
func TestMediaIndexerRoleGrantsListAndDropsAfterTest(t *testing.T) {
	admin := Pool(t, "roletest", roleTables, noReset)
	ctx := context.Background()
	var name string

	t.Run("역할", func(t *testing.T) {
		r := NewMediaIndexerRole(t, admin)
		name = r.Name
		if !strings.HasPrefix(name, mediaIndexerRolePrefix) {
			t.Errorf("역할 이름 %q, want 접두 %q", name, mediaIndexerRolePrefix)
		}
		const okSQL = "SELECT count(*) FROM stream_segments"
		var n int
		if err := r.Pool.QueryRow(ctx, okSQL).Scan(&n); err != nil {
			t.Fatalf("SELECT 오류 = %v, want nil", err)
		}
		const deniedSQL = "DELETE FROM stream_segments"
		if _, err := r.Pool.Exec(ctx, deniedSQL); !IsInsufficientPrivilege(err) {
			t.Errorf("DELETE 오류 = %v, want 42501", err)
		}
		r.RequireSent(t, map[string]string{"okSQL": okSQL})
		if r.sent.has(deniedSQL) {
			t.Error("실패한 DELETE 가 성공 기록에 남았다")
		}
		if got := r.sent.deniedStatements(); !slices.Equal(got, []string{deniedSQL}) {
			t.Errorf("거부 기록 = %q, want [%q] — RequireNoneDenied 가 보는 목록이다", got, deniedSQL)
		}
	})

	var exists bool
	if err := admin.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = $1)`, name).Scan(&exists); err != nil {
		t.Fatalf("역할 조회 실패: %v", err)
	}
	if exists {
		t.Errorf("역할 %q 가 시험 뒤에도 남았다", name)
	}
}

// 시험 역할은 1시간 뒤 로그인할 수 없다(C4 보안 검수 L5) — 실행이 강제 종료돼 Cleanup 이 돌지 않아도 남은 역할이
// 알려진 권한을 든 채 로그인 가능한 계정으로 오래 남지 않는다.
func TestMediaIndexerRoleExpiresWithinAnHour(t *testing.T) {
	admin := Pool(t, "roletest", roleTables, noReset)
	r := NewMediaIndexerRole(t, admin)

	var hasExpiry, inWindow bool
	err := admin.QueryRow(context.Background(), `
		SELECT rolvaliduntil IS NOT NULL,
		       COALESCE(rolvaliduntil > now() AND rolvaliduntil <= now() + interval '1 hour', false)
		FROM pg_roles WHERE rolname = $1`, r.Name).Scan(&hasExpiry, &inWindow)
	if err != nil {
		t.Fatalf("역할 만료 조회 실패: %v", err)
	}
	if !hasExpiry {
		t.Fatalf("역할 %s 에 VALID UNTIL 이 없다 — 강제 종료로 남으면 로그인 가능한 채 남는다", r.Name)
	}
	if !inWindow {
		t.Errorf("역할 %s 의 VALID UNTIL 이 지금부터 1시간 안이 아니다", r.Name)
	}
}

// Revoke 는 이 목록이 준 권한만 거둔다 — 문장에 붙여 넣는 인자라 목록 밖 값(다른 표 · 다른 권한 · SQL 조각)은
// 보내기 전에 거부한다(C4 보안 검수 L5).
func TestRevokeSQLAcceptsOnlyGrantedPairs(t *testing.T) {
	got, err := revokeSQL("UPDATE", "stream_sessions", "r1")
	if err != nil || got != "REVOKE UPDATE ON stream_sessions FROM r1" {
		t.Errorf(`revokeSQL("UPDATE", "stream_sessions") = %q, %v`, got, err)
	}
	for _, c := range []struct{ privilege, table string }{
		{"DELETE", "stream_segments"},
		{"UPDATE", "stream_cutoffs"},
		{"SELECT", "pg_authid"},
		{"select", "stream_segments"},
		{"SELECT, INSERT", "stream_segments"},
		{"SELECT", "stream_segments; DROP TABLE stream_segments"},
		{"ALL", "stream_segments"},
	} {
		if got, err := revokeSQL(c.privilege, c.table, "r1"); err == nil {
			t.Errorf("revokeSQL(%q, %q) = %q, want 거부", c.privilege, c.table, got)
		}
	}
}
