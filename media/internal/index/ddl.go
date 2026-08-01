package index

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// schemaDDL 은 stream_segments 의 정본 스키마다. 소유 = 1번(Media).
//
// 소유권 합의 (2026-08-01 kty·3번, ADR-0001 참조):
//   - 테이블 생성·DDL = 1번. 원래 3번 소유 예정(구 POK-35)이었으나 "우리가 기록하는 테이블은
//     우리가 만든다"로 위임받아, 검증 완료된 이 스키마(테스트 159건·실측 6회 통과)를 정본으로 승격했다.
//   - 컬럼 추가·변경·삭제 = 3번 승인 필수. 이 테이블을 3번(클립 서비스)이 읽기 때문이다.
//     계약-세그먼트인덱스 4절 참조.
//   - 프로덕션(RDS) 적용 절차는 AWS 배포 시점에 별도 결정(현재 미배포). 그 전까지는
//     ENSURE_SCHEMA 게이트로 로컬 compose 에서만 부팅 시 생성한다.
//
// UNIQUE(stream_id, local_path) 는 재기동 관통 멱등의 최후 방어선이다(U12 — 정본 포함으로 해소).
// 이 제약이 빠지면 인메모리 중복 방지가 크래시를 관통하지 못해 같은 파일이 두 번 들어간다(G3).
//
// UNIQUE 인덱스 이름 stream_segments_local_path_uq 는 이름 자체가 계약이다.
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
// Store 인터페이스 밖의 자유 함수인 이유(D7, G7): 스키마 부트스트랩은 영속 계약(Store)과
// 변경 이유가 다르다(SRP). 나중에 정식 마이그레이션 도구를 도입하게 되면 이 파일만 교체하면 되고,
// 인터페이스와 테스트용 가짜 구현은 하나도 안 바뀐다.
func EnsureSchema(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, schemaDDL); err != nil {
		return fmt.Errorf("stream_segments 스키마 생성 실패: %w", err)
	}
	return nil
}
