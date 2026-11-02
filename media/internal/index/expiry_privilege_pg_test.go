package index

// 만료 기동 권한 검사(POK-196 M6-4 c3 · 설계 7절 활성화 선행 조건 · D50). 운영 권한(30_grants.sql)이 아직
// 적용되지 않은 DB 에서 만료를 켜면 DELETE 가 42501 로 반쯤 지운 상태를 남긴다 — 기동 때 권한을 보고 하나라도
// 없으면 만료를 켜지 않는다(fail-closed).

import (
	"context"
	"slices"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// 사이드카 계정 권한(media_indexer 목록) 그대로면 빠진 것이 없다.
func TestMissingExpiryPrivilegesNoneUnderMediaIndexer(t *testing.T) {
	admin := newTestPool(t)
	role := pgtest.NewMediaIndexerRole(t, admin)
	missing, err := MissingExpiryPrivileges(context.Background(), role.Pool)
	if err != nil || len(missing) != 0 {
		t.Fatalf("MissingExpiryPrivileges = %v err=%v (빈 목록 기대)", missing, err)
	}
}

// D50 — 옛 권한(만료 몫 선부여 전)이면 빠진 권한을 이름(표:권한)으로 돌려준다. 부르는 쪽은 만료를 켜지 않는다.
func TestMissingExpiryPrivilegesReportsRevoked(t *testing.T) {
	admin := newTestPool(t)
	ctx := context.Background()
	role := pgtest.NewMediaIndexerRole(t, admin)
	for _, s := range []string{
		"REVOKE DELETE ON stream_sessions FROM " + role.Name,
		"REVOKE UPDATE ON stream_cutoffs FROM " + role.Name,
		"REVOKE DELETE ON broadcast_lifecycle_outbox FROM " + role.Name,
	} {
		if _, err := admin.Exec(ctx, s); err != nil {
			t.Fatalf("%s: %v", s, err)
		}
	}
	missing, err := MissingExpiryPrivileges(ctx, role.Pool)
	if err != nil {
		t.Fatalf("MissingExpiryPrivileges: %v", err)
	}
	want := []string{"broadcast_lifecycle_outbox:DELETE", "stream_cutoffs:UPDATE", "stream_sessions:DELETE"}
	if !slices.Equal(missing, want) {
		t.Fatalf("빠진 권한 %v (기대 %v)", missing, want)
	}
}

// 검사 목록의 원천은 사이드카 권한 목록(pgtest.MediaIndexerGrantSQL)과 갈라지지 않는다 — 검사가 보는 권한은 모두
// 사이드카 계정이 받는 권한이다(설계 13절 「목록이 둘이 되지 않게」).
func TestExpiryPrivilegesSubsetOfMediaIndexerGrants(t *testing.T) {
	granted := map[string]bool{}
	for _, s := range pgtest.MediaIndexerGrantSQL("r") {
		// GRANT A, B ON 표 TO r
		body := strings.TrimSuffix(strings.TrimPrefix(s, "GRANT "), " TO r")
		privs, table, ok := strings.Cut(body, " ON ")
		if !ok {
			t.Fatalf("GRANT 문장 꼴이 다르다: %q", s)
		}
		for _, p := range strings.Split(privs, ", ") {
			granted[table+":"+p] = true
		}
	}
	for _, p := range ExpiryPrivileges() {
		if !granted[p] {
			t.Errorf("만료 검사 권한 %s 가 사이드카 권한 목록에 없다", p)
		}
	}
	if len(ExpiryPrivileges()) != 6 {
		t.Fatalf("만료 권한 %d개 (여섯 기대 — 설계 7절 cc L8ⓑ)", len(ExpiryPrivileges()))
	}
}
