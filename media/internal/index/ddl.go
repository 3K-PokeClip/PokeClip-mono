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
