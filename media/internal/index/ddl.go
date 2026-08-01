package index

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// schemaDDL 은 로컬 개발 전용 임시 스키마다.
//
// TODO(POK-35): 3번(스키마 담당)이 소유하는 정본 마이그레이션으로 교체.
// 이 파일은 통째로 삭제 대상이며, 삭제해도 store.go / record.go 는 그대로 컴파일된다(G7).
// 프로덕션 경로 승격 금지.
//
// TODO(POK-35 인수인계 1순위): 정본 스키마에도 UNIQUE(stream_id, local_path) 가 반드시 있어야 한다(U12).
// 이 제약이 빠지면 재기동 관통 멱등의 최후 방어선이 사라진다. 인메모리 중복 방지는 크래시를
// 관통하지 못하기 때문이다(G3). 임시 DDL 로 만들어진 표를 지우는 절차도 함께 전달한다:
//
//	DROP TABLE IF EXISTS stream_segments;
//
// 이 DROP 을 하지 않으면 우리 임시 형상이 정본인 척 조용히 살아남는다.
//
// TODO(POK-35 인수인계 2순위): UNIQUE 인덱스 이름 stream_segments_local_path_uq 도 정본에 그대로 써야 한다.
// store.go 의 Insert 는 23505(unique_violation)를 받았을 때 제약 이름으로 "같은 파일 재삽입"(정상 멱등)과
// "seq 충돌"(단일 쓰기자 전제 붕괴)을 가른다. 이름이 달라지면 정상 멱등이 seq 충돌로 오분류되어
// 재적재 후 프로세스 종료 경로를 타게 된다.
//
// 이번 범위 밖으로 이월한 항목(PR "알려진 제약"에 함께 기재):
//   - 보안 M-1: ExistingPaths 가 스트림의 경로 전부를 메모리에 올린다. 창(window) 제한 없음.
//   - 보안 M-2: 사이드카가 POSTGRES_USER 를 그대로 쓴다. 이 표만 쓰는 전용 롤이 바람직하다.
//   - 미확인 3: 파일명 파서에 대한 fuzz 테스트 없음.
const schemaDDL = `
CREATE TABLE IF NOT EXISTS stream_segments (
    stream_id        text        NOT NULL,
    seq              bigint      NOT NULL,
    start_pts_ms     bigint      NOT NULL,
    start_wall_utc   timestamptz NOT NULL,
    duration_ms      int         NOT NULL,
    s3_key           text        NOT NULL,
    local_path       text,
    upload_state     text        NOT NULL DEFAULT 'pending',
    uploaded_at      timestamptz,
    bytes            bigint,
    is_discontinuity boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (stream_id, seq)
);

CREATE UNIQUE INDEX IF NOT EXISTS stream_segments_local_path_uq
    ON stream_segments (stream_id, local_path);
`

// EnsureSchema 는 표가 없으면 만든다.
//
// Store 인터페이스 밖의 자유 함수인 이유(D7, G7): POK-35 에서 ddl.go 를 통째로 지울 때
// 인터페이스와 테스트용 가짜 구현이 하나도 안 바뀌게 하려는 것이다. 삭제 비용을 미리 0으로 만들어 뒀다.
func EnsureSchema(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, schemaDDL); err != nil {
		return fmt.Errorf("임시 스키마 생성 실패: %w", err)
	}
	return nil
}
