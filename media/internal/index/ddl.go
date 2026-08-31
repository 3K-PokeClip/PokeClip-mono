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
//   - playback_* 4컬럼 + CHECK 2종 = 계약-세그먼트인덱스 5-5절(3번 승인 2026-08-31, 위키 PR #100).
//     ③(재생 렌디션)의 키·게이팅 상태다. playback_upload_state 는 공정별 상태가 아니라
//     "되감기 목록에 실어도 되는가" 가용 플래그 하나다 — uploaded 는 정규화→추출→PUT→CAS 전부 완료.
//     주의: ② 로컬 삭제 조건에 playback_upload_state='uploaded' 를 추가하는 3절 불변식 2 개정분은
//     ③ 추출 파이프라인과 함께 코드로 강제한다 — 추출기가 없는 지금 강제하면 pending 이 영원해
//     로컬이 안 지워진다(디스크 포화). 여기서는 스키마만 착지시킨다.
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
    playback_s3_key        text,
    playback_upload_state  text        NOT NULL DEFAULT 'pending',
    playback_uploaded_at   timestamptz,
    playback_bytes         bigint,
    PRIMARY KEY (stream_id, seq)
);

CREATE UNIQUE INDEX IF NOT EXISTS stream_segments_local_path_uq
    ON stream_segments (stream_id, local_path);

-- 조각당 키 2개 (계약-세그먼트인덱스 5-5절, 3번 승인 2026-08-31 · ADR-057).
-- 위 CREATE TABLE 은 IF NOT EXISTS 라 "이미 존재하는" 표에는 컬럼을 붙이지 않는다.
-- 그래서 기존 표 전파는 아래가 담당한다(신규 표에서는 무해한 no-op).
--
-- ADD COLUMN IF NOT EXISTS 를 그대로 쓰지 않는 이유: 그 구문도 존재 확인 "전에"
-- ACCESS EXCLUSIVE 락부터 잡는다 — 기동마다 실행되는 EnsureSchema 특성상, 장기 SELECT 뒤에
-- ALTER 가 큐잉되면 그 뒤 모든 쿼리가 줄줄이 막힌다. CHECK 와 같은 DO 블록 패턴으로
-- 카탈로그를 먼저 보고, 컬럼이 없을 때(사실상 최초 1회)만 ALTER 를 실행한다.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'stream_segments'
                     AND column_name = 'playback_s3_key') THEN
        ALTER TABLE stream_segments
            ADD COLUMN playback_s3_key        text,
            ADD COLUMN playback_upload_state  text        NOT NULL DEFAULT 'pending',
            ADD COLUMN playback_uploaded_at   timestamptz,
            ADD COLUMN playback_bytes         bigint;
    END IF;
END $do$;

-- CHECK 2종 (upload_state 는 계약 5-3 확정분인데 실물에 없던 것을 이번에 일치시킨다).
-- PostgreSQL 은 ADD CONSTRAINT 에 IF NOT EXISTS 가 없다 — EnsureSchema 가 기동마다 전체를
-- 실행하므로, 감싸지 않으면 두 번째 기동부터 중복 제약명(42710)으로 실패한다.
-- 제약명은 DB 전역에서 유일하지 않으므로 conrelid(대상 표)·contype 까지 함께 본다 —
-- conname 만 보면 다른 표의 동명 제약에 속아 추가를 조용히 건너뛴다.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'stream_segments'::regclass
                     AND conname  = 'stream_segments_upload_state_chk'
                     AND contype  = 'c') THEN
        ALTER TABLE stream_segments
            ADD CONSTRAINT stream_segments_upload_state_chk
            CHECK (upload_state IN ('pending','uploaded','failed'));
    END IF;
END $do$;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'stream_segments'::regclass
                     AND conname  = 'stream_segments_playback_upload_state_chk'
                     AND contype  = 'c') THEN
        ALTER TABLE stream_segments
            ADD CONSTRAINT stream_segments_playback_upload_state_chk
            CHECK (playback_upload_state IN ('pending','uploaded','failed'));
    END IF;
END $do$;
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
