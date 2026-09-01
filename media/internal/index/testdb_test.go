package index

import (
	"context"
	"errors"
	"fmt"
	"os"
	"regexp"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// ★ 왜 전용 DB 를 따로 만드는가
// PendingUploads 와 CountBacklog 는 스트림 필터가 없는 **표 전역 조회**다 — 스위퍼가
// 표 전체를 훑는 것이 계약이기 때문이다(설계 2.1절). 그래서 upload_store_test.go 의
// 판정은 표에 다른 행이 하나도 없어야 결정적이다. 개발용 DB 의 stream_segments 를 비우면
// 남의 데이터를 지우게 되므로, 같은 서버에 전용 DB 를 만들고 그 안에서만 TRUNCATE 한다.
//
// store 테스트도 같은 풀을 쓴다 — itest-* 행이 공용 DB 에 생기는 경로가 사라졌고,
// 그래서 잔재 sweep 이 불필요하다. upload_store_test.go 에 sweep 이 없는 이유도 같다:
// 그 파일은 전용 DB 에만 붙고 풀 생성마다 TRUNCATE 하므로, 남는 행은 전용 DB 안에 있고
// 다음 회차 TRUNCATE 가 덮는다 — 공용 DB 오염 경로가 0 이다.
//
// ddl.go 를 바꾸면 이 전용 DB 는 CREATE TABLE IF NOT EXISTS 때문에 옛 스키마를 유지한다 —
// 단 컬럼 추가와 "새 이름의 제약 추가"는 schemaDDL 의 ALTER … ADD COLUMN IF NOT EXISTS / DO 블록이
// 기존 표에도 전파한다(둘 다 카탈로그 존재 여부만 보는 멱등 가드다, 2026-08-31). 컬럼 타입 변경이나
// 기존 제약의 정의(조건)를 같은 이름으로 바꾸는 변경은 여전히 DROP DATABASE pokeclip_uploadtest
// 후 재실행해야 한다 — 가드가 "이미 있다"로 판단해 새 정의를 적용하지 않기 때문이다.
//
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

// DSN 예:
//
//	set -a; . ./.env; set +a
//	export PG_DSN="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
//
// PG_DSN 은 관리 접속이다. CREATE DATABASE 한 문장에만 쓰이고, 표에 대한 읽기·쓰기는
// 전부 전용 테스트 DB 안에서만 일어난다.
//
// TestMain 은 릴리스 게이트용 스위치다.
//
// 이 패키지의 SQL 통합 케이스는 PG_DSN 이 없으면 전부 skip 되는데, skip 은 go test 에서
// 성공으로 집계된다 — DB 를 안 띄운 실행이 "녹색"으로 보이고 SQL 은 한 줄도 검증되지 않는다.
// REQUIRE_PG=1 이면 그 상황을 실패로 바꾼다. 기본값(미설정)은 종전대로 skip 이라
// DB 없는 개발 머신에서도 나머지 테스트가 그대로 돈다.
func TestMain(m *testing.M) {
	if os.Getenv("REQUIRE_PG") == "1" && os.Getenv("PG_DSN") == "" {
		fmt.Fprintln(os.Stderr,
			"REQUIRE_PG=1 인데 PG_DSN 이 비어 있다 — SQL 통합 케이스가 전량 skip 된다. 게이트 실패.")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

func newTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()

	dsn := os.Getenv("PG_DSN")
	if dsn == "" {
		t.Skip("PG_DSN 미설정 — DB 통합 테스트를 건너뛴다")
	}
	name := os.Getenv("PG_TEST_DB")
	if name == "" {
		name = defaultTestDB
	}
	if !safeDBName.MatchString(name) {
		t.Fatalf("PG_TEST_DB=%q 는 허용되지 않는 이름이다 (소문자·숫자·밑줄만)", name)
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
	// 42P04(이미 있다)로 조용히 통과하고, 뒤따르는 TRUNCATE 가 공용 개발 DB 에 나간다.
	if cfg.ConnConfig.Database == name {
		t.Fatalf("PG_TEST_DB(미설정 시 기본값)=%q 가 PG_DSN 의 DB %q 와 같다 — 그대로 두면 공용 DB 를 TRUNCATE 한다",
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
	// 채택 전에 우리 것인지를 가른다 — TRUNCATE 를 내기 전에 멈추는 것이 요점이다.
	//
	// 표의 개수·이름으로는 못 가른다: 공용 개발 DB 는 public 에 stream_segments 하나뿐이라
	// 우리가 만든 DB 와 모양이 완전히 같다. 그래서 모양이 아니라 우리가 심은 표식을 본다.
	// to_regclass 는 아래 TRUNCATE 와 같은 이름 해석(search_path)을 쓴다 — 표식을 확인한 자리와
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

	if err := EnsureSchema(ctx, pool); err != nil {
		t.Fatalf("EnsureSchema 실패: %v", err)
	}
	// M2 신설 표까지 함께 비운다 — 네 표를 한 문장에 넣으면 상호 FK(segments→sessions)에
	// CASCADE 없이도 통과한다.
	if _, err := pool.Exec(ctx,
		"TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions"); err != nil {
		t.Fatalf("TRUNCATE 실패: %v", err)
	}
	return pool
}

func isSQLState(err error, code string) bool {
	var pgErr interface{ SQLState() string }
	return errors.As(err, &pgErr) && pgErr.SQLState() == code
}
