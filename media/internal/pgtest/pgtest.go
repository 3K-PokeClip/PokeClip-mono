// Package pgtest 는 실 PostgreSQL 을 쓰는 테스트가 붙을 **전용 테스트 DB** 를 부트스트랩한다
// (동일 이름 가드 · 소유 표식 · 스키마 · 비우기). index 패키지의 테스트 파일에 갇혀 있던 것을
// 꺼낸 것이라, 여기 있는 절차와 가드는 그쪽에서 쓰던 것과 같다.
//
// 이 패키지는 **스키마도 표 목록도 모른다** — 무엇을 만들고(ensure) 무엇을 비울지(reset)는
// 호출자가 함수로 준다. 그래서 index 를 임포트하지 않는다(임포트 0 = 순환 차단이자,
// 같은 부트스트랩을 다른 패키지가 쓸 수 있는 이유다).
package pgtest

import (
	"context"
	"errors"
	"os"
	"regexp"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// DB 이름은 PG_TEST_DB 로 바꿀 수 있다(기본 pokeclip_uploadtest).
const defaultTestDB = "pokeclip_uploadtest" // 값은 기존 로컬 DB 를 그대로 재사용하려고 유지한다. 이름만 패키지 공용으로 넓혔다.

// safeDBName 은 식별자를 그대로 SQL 에 이어 붙이기 전의 화이트리스트다.
// CREATE DATABASE 는 파라미터 바인딩을 지원하지 않아 문자열 결합이 불가피하므로,
// 무엇을 막을지 나열하는 대신 무엇을 허용할지 좁게 못 박는다.
var safeDBName = regexp.MustCompile(`^[a-z_][a-z0-9_]{0,62}$`)

// pgDuplicateDatabase 는 "이미 있다"는 SQLSTATE 다. 재실행에서 정상이다.
const pgDuplicateDatabase = "42P04"

// testDBMarker 는 "이 DB 는 이 테스트가 만들었다"는 소유 표식이다. 새로 만든 DB 안에만 심고,
// 이미 있는 DB 를 채택할 때 이 표가 있는지로 우리 것인지를 가른다.
// 열도 내용도 없는 빈 표다 — 존재 자체가 계약이라 담을 것이 없다.
const testDBMarker = "pokeclip_testdb_marker"

// bootstrapTimeout 은 아래 부트스트랩(생성·채택 검사·스키마·비우기)에만 걸리는 시한이다.
// 테스트 본문의 질의에는 걸리지 않는다.
const bootstrapTimeout = 30 * time.Second

// baseDBName 은 접미를 붙이기 전의 밑이름이다.
func baseDBName() string {
	if name := os.Getenv("PG_TEST_DB"); name != "" {
		return name
	}
	return defaultTestDB
}

// dbName 은 밑이름에 패키지별 접미를 붙인 최종 DB 이름이다.
// 접미가 비면 밑이름 그대로다 — 기존 DB 를 그대로 재사용하는 패키지(index)를 위해서다.
func dbName(base, suffix string) string {
	if suffix == "" {
		return base
	}
	return base + "_" + suffix
}

// Pool 은 접미로 가른 전용 테스트 DB 에 붙은 풀을 돌려준다. 풀은 t.Cleanup 이 닫는다.
//
// suffix 는 패키지별 DB 를 가르는 접미다 — go test ./... 는 테스트 바이너리를 패키지별로
// 병렬 실행하므로, 두 패키지가 같은 DB 를 잡으면 reset 이 서로의 행을 지운다. 빈 접미는
// 밑이름을 그대로 쓴다. 같은 이유로 **패키지 안에서는 t.Parallel 을 쓰지 않는다** — 풀을
// 만들 때마다 비우므로 같은 DB 를 보는 케이스가 병렬이면 서로의 행을 지운다.
//
// ensure 는 스키마를 보장하고 reset 은 표를 비운다. 둘 다 호출자가 준다(이 패키지는 스키마를
// 모른다). 순서는 ensure → reset 이다 — 아직 없는 표는 비울 수 없다.
//
// DSN 예:
//
//	set -a; . ./.env; set +a
//	export PG_DSN="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
//
// PG_DSN 은 관리 접속이다. CREATE DATABASE 한 문장에만 쓰이고, 표에 대한 읽기·쓰기는
// 전부 전용 테스트 DB 안에서만 일어난다.
func Pool(
	t *testing.T,
	suffix string,
	ensure func(ctx context.Context, pool *pgxpool.Pool) error,
	reset func(ctx context.Context, pool *pgxpool.Pool) error,
) *pgxpool.Pool {
	t.Helper()

	dsn := os.Getenv("PG_DSN")
	if dsn == "" {
		t.Skip("PG_DSN 미설정 — DB 통합 테스트를 건너뛴다")
	}
	name := dbName(baseDBName(), suffix)
	if !safeDBName.MatchString(name) {
		t.Fatalf("전용 테스트 DB 이름 %q 는 허용되지 않는다 (소문자·숫자·밑줄만, 63자 이내) — PG_TEST_DB 또는 접미를 고쳐라", name)
	}

	// 부트스트랩 전용 시한이다. 여기의 질의는 전부 로컬 compose 를 상대로 한 짧은 것들이라,
	// DB 가 응답을 멈추면 테스트 전체가 무기한 매달리는 대신 이 시한에서 끊긴다.
	// 반환하는 풀에는 이 ctx 가 남지 않는다 — 풀의 실제 접속은 테스트가 준 ctx 로 그때 열린다.
	ctx, cancel := context.WithTimeout(context.Background(), bootstrapTimeout)
	defer cancel()

	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatalf("DSN 파싱 실패: %v", err)
	}
	// 빈 DB 명을 먼저 막는다. PG_DSN 에 DB 를 안 적으면 pgx 는 Database 를 빈 문자열로 둔 채
	// 접속을 서버에 맡기고, 서버는 롤 이름과 같은 DB 를 골라 준다. 그 이름이 공용 개발 DB 일
	// 수 있는데, 아래 동일 이름 가드는 ""(빈 값) != name 이라 통과시켜 버린다 — 가드 무력화다.
	//
	// 단 이 가드가 걸리는 건 PGDATABASE 가 없을 때뿐이다: PGDATABASE 가 설정된 환경에서는
	// pgx 가 그 값을 Database 에 채워 넣어 빈 값이 아니게 되고, 접속 대상도 암묵이 아니라
	// 그 이름으로 정해진다 — 그때는 바로 아래 동일 이름 가드가 판정한다.
	if cfg.ConnConfig.Database == "" {
		t.Fatal("PG_DSN 에 DB 명이 없다 — 명시하라. 빈 값이면 접속 대상이 롤 이름으로 암묵 결정돼 공용 DB 가드가 무력화된다")
	}
	// 가드는 CREATE DATABASE 보다 반드시 앞이어야 한다. 두 이름이 같으면 CREATE 는
	// 42P04(이미 있다)로 조용히 통과하고, 뒤따르는 reset 이 공용 개발 DB 에 나간다.
	if cfg.ConnConfig.Database == name {
		t.Fatalf("전용 테스트 DB %q 가 PG_DSN 의 DB %q 와 같다 — 그대로 두면 공용 DB 를 비운다. PG_TEST_DB 로 다른 이름을 줘라",
			name, cfg.ConnConfig.Database)
	}

	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatalf("관리용 풀 생성 실패: %v", err)
	}
	defer admin.Close()

	createdDB := true
	if _, err := admin.Exec(ctx, "CREATE DATABASE "+name); err != nil {
		if !isSQLState(err, pgDuplicateDatabase) {
			t.Fatalf("전용 테스트 DB %q 생성 실패 (로컬 compose 의 PG_DSN 롤에 CREATEDB 권한이 필요하다): %v", name, err)
		}
		createdDB = false // 이미 있던 DB 다 — 누가 만든 것인지는 아래 소유 표식이 가른다.
	}

	cfg.ConnConfig.Database = name
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("테스트 DB 풀 생성 실패: %v", err)
	}
	t.Cleanup(pool.Close)

	// 위 CREATE DATABASE 는 42P04(이미 있다)를 정상으로 넘긴다 — 재실행을 위해서다. 그런데
	// 그 관용은 "PG_TEST_DB 가 이미 존재하는 남의 DB 를 가리키는" 경우까지 함께 통과시키므로,
	// 채택 전에 우리 것인지를 가른다 — 비우기를 내기 전에 멈추는 것이 요점이다.
	//
	// 표의 개수·이름으로는 못 가른다: 공용 개발 DB 는 public 에 stream_segments 하나뿐이라
	// 우리가 만든 DB 와 모양이 완전히 같다. 그래서 모양이 아니라 우리가 심은 표식을 본다.
	// to_regclass 는 뒤따르는 비우기와 같은 이름 해석(search_path)을 쓴다 — 표식을 확인한 자리와
	// 표를 비우러 가는 자리가 어긋나지 않는다.
	if createdDB {
		if _, err := pool.Exec(ctx, "CREATE TABLE "+testDBMarker+" ()"); err != nil {
			t.Fatalf("소유 표식 %q 생성 실패: %v", testDBMarker, err)
		}
	} else {
		var hasMarker bool
		if err := pool.QueryRow(ctx, "SELECT to_regclass($1) IS NOT NULL", testDBMarker).Scan(&hasMarker); err != nil {
			t.Fatalf("테스트 DB 채택 검사 실패: %v", err)
		}
		if !hasMarker {
			t.Fatalf("이미 있는 DB %q 에 소유 표식 %q 가 없다 — 이 테스트가 만든 DB 가 아닐 수 있다(남의 DB / 표식 도입 전의 옛 전용 DB / 이전 부트스트랩이 표식을 심기 전에 중단된 잔재 중 하나다). 가장 안전한 조치는 아직 없는 새 이름을 PG_TEST_DB 로 주는 것이다. 이 DB 가 전용 테스트 DB 라고 직접 확인한 경우에만 DROP DATABASE 로 지우고 다시 돌려라 — 확인 없이 지우면 남의 데이터가 사라진다",
				name, testDBMarker)
		}
	}

	if err := ensure(ctx, pool); err != nil {
		t.Fatalf("스키마 보장(ensure) 실패: %v", err)
	}
	if err := reset(ctx, pool); err != nil {
		t.Fatalf("표 비우기(reset) 실패: %v", err)
	}
	return pool
}

func isSQLState(err error, code string) bool {
	var pgErr interface{ SQLState() string }
	return errors.As(err, &pgErr) && pgErr.SQLState() == code
}
