package pgtest

import (
	"context"
	"maps"
	"slices"
	"testing"
)

// 양성 대조 — 덫이 정말 덫인가. 스키마를 한정하지 않은 꼴은 덫마다 그 덫을 부르고(부른 신분이 기록에 남는다),
// pg_catalog 로 한정하고 형을 맞춘 꼴은 하나도 부르지 않는다. 시험이 끝나면 덫 · 기록 표 · 가짜 앱 사용자가 남지
// 않는다. 이 대조가 없으면 role 시험의 「덫 0회」 가 덫이 무뎌서인지 SQL 이 안전해서인지 가를 수 없다.
func TestSearchPathTrapsCatchBareNamesOnly(t *testing.T) {
	admin := Pool(t, "roletest", roleTables, noReset)
	ctx := context.Background()
	var role string

	t.Run("덫", func(t *testing.T) {
		traps := PlantSearchPathTraps(t, admin)
		role = traps.role
		bare := []string{
			"SELECT pg_advisory_xact_lock(hashtext('k'))",
			"SELECT hashtext('k'::varchar)",
			"SELECT unnest('{a}'::text[])",
			"SELECT unnest('{1}'::bigint[])",
			"SELECT 2 * interval '1 second'",
			"SELECT 1 FROM pg_class WHERE oid = 'stream_segments'::regclass",
			"SELECT 1 FROM information_schema.columns WHERE table_name = 'stream_segments'",
		}
		for _, s := range bare {
			if _, err := admin.Exec(ctx, s); err != nil {
				t.Fatalf("%q 오류 = %v, want nil", s, err)
			}
		}
		got := slices.Sorted(maps.Keys(traps.calls(t)))
		want := make([]string, 0, len(searchPathTraps))
		for _, tr := range searchPathTraps {
			want = append(want, tr.name)
		}
		slices.Sort(want)
		if !slices.Equal(got, want) {
			t.Fatalf("맨 이름 꼴이 부른 덫 = %q, want 전부 %q", got, want)
		}

		if _, err := traps.conn.Exec(ctx, "TRUNCATE "+trapLogTable); err != nil {
			t.Fatalf("덫 기록 비우기 실패: %v", err)
		}
		qualified := []string{
			"SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('k')::bigint)",
			"SELECT pg_catalog.hashtext('k'::varchar)",
			"SELECT pg_catalog.unnest('{a}'::text[])",
			"SELECT 2::double precision * interval '1 second'",
			"SELECT 1 FROM pg_class WHERE oid = 'stream_segments'::regclass::oid",
			"SELECT 1 FROM information_schema.columns WHERE table_name::name = 'stream_segments'",
		}
		for _, s := range qualified {
			if _, err := admin.Exec(ctx, s); err != nil {
				t.Fatalf("%q 오류 = %v, want nil", s, err)
			}
		}
		traps.RequireNotCalled(t)
	})

	var left int
	if err := admin.QueryRow(ctx, `SELECT (SELECT count(*) FROM pg_roles WHERE rolname = $1)
		+ (SELECT count(*) FROM pg_proc WHERE proname LIKE 'pc\_trap\_%')
		+ (SELECT count(*) FROM pg_operator WHERE oprnamespace = 'public'::regnamespace)
		+ (SELECT count(*) FROM pg_class WHERE relname = 'pc_search_path_trap_log')`, role).Scan(&left); err != nil {
		t.Fatalf("잔여 조회 실패: %v", err)
	}
	if left != 0 {
		t.Errorf("시험 뒤 덫 · 기록 표 · 가짜 앱 사용자 %d개가 남았다, want 0", left)
	}
}
