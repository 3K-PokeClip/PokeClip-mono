package broadcast

// 발행 게이트 권한 확인(POK-196 M6-8 ④ · 설계 M6 7절 활성화 선행 조건 · D50). outbox 권한이 하나라도 없으면 기동 조립이
// 게이트를 끈다 — 개시 · phase 4 가 outbox 문장에서 42501 로 막히지 않게 하는 fail-closed 다.

import (
	"context"
	"slices"
	"strings"
	"testing"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
)

// 사이드카 계정 권한(media_indexer 목록) 그대로면 빠진 것이 없다.
func TestMissingPrivilegesNoneUnderMediaIndexer(t *testing.T) {
	role := pgtest.NewMediaIndexerRole(t, newTestPool(t))
	missing, err := MissingPrivileges(context.Background(), role.Pool)
	if err != nil || len(missing) != 0 {
		t.Fatalf("MissingPrivileges = %v err=%v (빈 목록 기대)", missing, err)
	}
}

// D50 — 거둔 권한을 「표:권한」 이름으로 돌려준다(이름 순).
func TestMissingPrivilegesReportsRevoked(t *testing.T) {
	role := pgtest.NewMediaIndexerRole(t, newTestPool(t))
	role.Revoke(t, "UPDATE", "broadcast_lifecycle_outbox")
	role.Revoke(t, "INSERT", "broadcast_lifecycle_outbox")
	missing, err := MissingPrivileges(context.Background(), role.Pool)
	want := []string{"broadcast_lifecycle_outbox:INSERT", "broadcast_lifecycle_outbox:UPDATE"}
	if err != nil || !slices.Equal(missing, want) {
		t.Fatalf("MissingPrivileges = %v err=%v, want %v", missing, err, want)
	}
}

// 검사가 보는 권한은 모두 사이드카 계정이 받는 권한이고(목록이 갈라지지 않게) outbox 네 권한 전부다.
func TestPrivilegesMatchMediaIndexerGrants(t *testing.T) {
	var granted []string
	for _, s := range pgtest.MediaIndexerGrantSQL("r") {
		body := strings.TrimSuffix(strings.TrimPrefix(s, "GRANT "), " TO r")
		privs, table, _ := strings.Cut(body, " ON ")
		if table == outboxTable {
			granted = strings.Split(privs, ", ")
		}
	}
	got := slices.Clone(outboxPrivileges[:])
	slices.Sort(granted)
	if !slices.Equal(got, granted) {
		t.Fatalf("검사 권한 %v, 사이드카 권한 목록 %v — 같아야 한다", got, granted)
	}
}
