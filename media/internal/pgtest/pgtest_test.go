package pgtest

import (
	"context"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

// 접미는 패키지별 전용 DB 를 가르는 유일한 수단이다 — go test ./... 는 테스트 바이너리를
// 패키지별로 병렬 실행하므로, 같은 이름의 DB 를 두 바이너리가 잡으면 서로의 행을 TRUNCATE 한다.
func TestDBNameAppendsSuffix(t *testing.T) {
	const want = "pokeclip_uploadtest_session"
	if got := dbName("pokeclip_uploadtest", "session"); got != want {
		t.Fatalf("dbName = %q, want %q", got, want)
	}
}

// 접미가 빈 문자열이면 밑줄도 붙지 않는다 — index 패키지가 기존 로컬 DB(pokeclip_uploadtest)를
// 그대로 재사용하는 근거다. 밑줄이 남으면 옛 DB 를 버리고 새 DB 를 만들게 된다.
func TestDBNameWithoutSuffixKeepsBase(t *testing.T) {
	const want = "pokeclip_uploadtest"
	if got := dbName("pokeclip_uploadtest", ""); got != want {
		t.Fatalf("dbName = %q, want %q", got, want)
	}
}

// 이름은 CREATE DATABASE 에 문자열로 이어 붙는다(파라미터 바인딩 불가) — 이 화이트리스트가
// 유일한 방벽이므로, 접미를 붙여 조립한 최종 이름도 여기를 통과해야 한다.
func TestSafeDBNameAcceptsOnlyAssembledIdentifiers(t *testing.T) {
	cases := []struct {
		name string
		want bool
	}{
		{"pokeclip_uploadtest", true},
		{"pokeclip_uploadtest_session", true},
		{"pokeclip_uploadtest_indexer", true},
		{strings.Repeat("a", 63), true},
		{strings.Repeat("a", 64), false}, // PostgreSQL 식별자 상한
		{"pokeclip_uploadtest; DROP DATABASE postgres", false},
		{"Pokeclip_Uploadtest", false},
		{"1pokeclip", false},
		{"", false},
	}
	for _, c := range cases {
		if got := safeDBName.MatchString(c.name); got != c.want {
			t.Errorf("safeDBName.MatchString(%q) = %v, want %v", c.name, got, c.want)
		}
	}
}

// pgtest 는 스키마도 표 목록도 모른다 — 호출자가 준 ensure 와 reset 을 그 순서로 부르는 것이
// 계약이다. 순서가 뒤집히면 아직 없는 표를 비우게 된다.
func TestPoolRunsInjectedEnsureThenReset(t *testing.T) {
	var calls []string
	ensure := func(ctx context.Context, pool *pgxpool.Pool) error {
		calls = append(calls, "ensure")
		_, err := pool.Exec(ctx, "CREATE TABLE IF NOT EXISTS pgtest_selftest ()")
		return err
	}
	reset := func(ctx context.Context, pool *pgxpool.Pool) error {
		calls = append(calls, "reset")
		_, err := pool.Exec(ctx, "TRUNCATE pgtest_selftest")
		return err
	}

	// PG_DSN 이 없으면 Pool 안에서 skip 된다.
	pool := Pool(t, "selftest", ensure, reset)

	if got, want := strings.Join(calls, ","), "ensure,reset"; got != want {
		t.Fatalf("주입 호출 순서 = %q, want %q", got, want)
	}

	var current string
	if err := pool.QueryRow(context.Background(), "SELECT current_database()").Scan(&current); err != nil {
		t.Fatalf("current_database 조회 실패: %v", err)
	}
	if want := dbName(baseDBName(), "selftest"); current != want {
		t.Fatalf("접속한 DB = %q, want %q — 접미가 붙은 전용 DB 여야 한다", current, want)
	}
}
