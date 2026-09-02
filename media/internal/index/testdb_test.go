package index

import (
	"context"
	"errors"
	"fmt"
	"os"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/3K-PokeClip/pokeclip-mono/media/internal/pgtest"
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
// 부트스트랩 절차(DB 이름·동일 이름 가드·소유 표식) 본체는 internal/pgtest 에 있다 —
// 이 패키지는 스키마(EnsureSchema)와 비울 표(resetTables)만 준다.

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

// newTestPool 은 이 패키지 전용 테스트 DB 에 붙은 풀이다. 접미가 빈 문자열인 이유는 위 ★ 의
// pokeclip_uploadtest 를 그대로 재사용하기 위해서다 — 접미가 붙으면 기존 로컬 DB 가 버려진다.
func newTestPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	return pgtest.Pool(t, "", EnsureSchema, resetTables)
}

// resetTables 는 케이스 사이에 표를 비운다. M2 신설 표까지 함께 비운다 — 네 표를 한 문장에
// 넣으면 상호 FK(segments→sessions)에 CASCADE 없이도 통과한다.
func resetTables(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx,
		"TRUNCATE stream_segments, stream_cutoffs, stream_published_gaps, stream_sessions")
	return err
}

func isSQLState(err error, code string) bool {
	var pgErr interface{ SQLState() string }
	return errors.As(err, &pgErr) && pgErr.SQLState() == code
}
