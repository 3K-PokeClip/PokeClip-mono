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

-- 인덱스는 이름이 없을 때만 만든다. CREATE INDEX IF NOT EXISTS 는 이름을 확인하기 전에 표를 SHARE 로 잠가, 이미
-- 수렴한 DB 에 다시 돌려도(기동마다 · 운영 한 창 재실행) 표 잠금을 잡고 그 사이 사이드카 쓰기와 교착할 수 있다.
-- 아래 인덱스는 모두 이 이디엄이다 — 수렴한 재실행은 장부 표를 잠그지 않는다.
DO $do$
BEGIN
    IF pg_catalog.to_regclass('stream_segments_local_path_uq') IS NULL THEN
        CREATE UNIQUE INDEX stream_segments_local_path_uq ON stream_segments (stream_id, local_path);
    END IF;
END $do$;

-- 조각당 키 2개 (계약-세그먼트인덱스 5-5절, 3번 승인 2026-08-31 · ADR-057).
-- 위 CREATE TABLE 은 IF NOT EXISTS 라 "이미 존재하는" 표에는 컬럼을 붙이지 않는다.
-- 그래서 기존 표 전파는 아래가 담당한다(신규 표에서는 무해한 no-op).
--
-- ADD COLUMN IF NOT EXISTS 를 그대로 쓰지 않는 이유: 그 구문도 존재 확인 "전에"
-- ACCESS EXCLUSIVE 락부터 잡는다 — 기동마다 실행되는 EnsureSchema 특성상, 장기 SELECT 뒤에
-- ALTER 가 큐잉되면 그 뒤 모든 쿼리가 줄줄이 막힌다. CHECK 와 같은 DO 블록 패턴으로
-- 카탈로그를 먼저 보고, 컬럼이 없을 때(사실상 최초 1회)만 ALTER 를 실행한다.
--
-- 카탈로그 확인의 비교는 형을 맞춘다(sql_identifier 열은 ::name, oid 열 쪽 regclass 는 ::oid). 맞추지 않으면
-- pg_catalog 에 정확한 판이 없어, 앱 사용자가 public 에 심은 같은 형의 = 연산자가 먼저 골린다 — 이 문장은
-- 운영에서 media_owner 로 돈다(search_path 가로채기, media/README.md).
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema::name = pg_catalog.current_schema()
                     AND table_name::name = 'stream_segments'
                     AND column_name::name = 'playback_s3_key') THEN
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
                   WHERE conrelid = 'stream_segments'::regclass::oid
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
                   WHERE conrelid = 'stream_segments'::regclass::oid
                     AND conname  = 'stream_segments_playback_upload_state_chk'
                     AND contype  = 'c') THEN
        ALTER TABLE stream_segments
            ADD CONSTRAINT stream_segments_playback_upload_state_chk
            CHECK (playback_upload_state IN ('pending','uploaded','failed'));
    END IF;
END $do$;

-- ============================================================================
-- 되감기 경로 M2 (POK-168 · POK-193) — 계약-세그먼트인덱스 6-1 승인분(3번 승인 2026-09-01,
-- 위키 #108·#110) + 1번 소유 표 3종(승인 표면 아님 — 6절 통보 완료).
-- 순서가 계약이다: stream_sessions 가 먼저다 — 아래 FK 가 참조한다.
-- ============================================================================

-- 세션(회차) 장부 — 설계 6.2. 1번 소유.
CREATE TABLE IF NOT EXISTS stream_sessions (
    session_id text PRIMARY KEY, stream_id text NOT NULL,
    started_at timestamptz NOT NULL, ending_at timestamptz, ended_at timestamptz,
    state text NOT NULL DEFAULT 'live',
    end_reason text,
    first_pdt timestamptz, ready_at timestamptz,
    first_seq bigint, last_seq bigint,
    target_duration int NOT NULL DEFAULT 6,
    init_s3_key text, init_sha256 bytea, init_bytes bigint, init_uploaded_at timestamptz,
    writer_fence text, fence_expires_at timestamptz,
    manifest_gen bigint NOT NULL DEFAULT 0, manifest_etag text,
    published_seq bigint NOT NULL DEFAULT -1, published_at timestamptz,
    discontinuity_base bigint NOT NULL DEFAULT 0,
    dvr_state text NOT NULL DEFAULT 'open',
    dvr_s3_key text, dvr_etag text, dvr_sha256 bytea,
    dvr_first_seq bigint, dvr_last_seq bigint, dvr_disc_base bigint,
    dvr_freezing_at timestamptz, dvr_frozen_at timestamptz,
    vod_state text NOT NULL DEFAULT 'none',
    vod_s3_key text, vod_etag text, vod_sha256 bytea,
    vod_first_seq bigint, vod_last_seq bigint,
    inherits_session text REFERENCES stream_sessions(session_id),
    CONSTRAINT stream_sessions_state_chk     CHECK (state IN ('live','ending','ended')),
    CONSTRAINT stream_sessions_dvr_state_chk CHECK (dvr_state IN ('open','freezing','frozen','freeze_failed')),
    CONSTRAINT stream_sessions_vod_state_chk CHECK (vod_state IN ('none','freezing','frozen','frozen_with_gaps','freeze_failed'))
);
-- state='live' 만 보는 이유: state <> 'ended' 로 두면 ending 세션이 새 세션 INSERT 를 막아
-- 방송 중 되감기를 새로 못 연다(설계 6.2).
DO $do$
BEGIN
    IF pg_catalog.to_regclass('stream_sessions_stream_idx') IS NULL THEN
        CREATE INDEX stream_sessions_stream_idx ON stream_sessions (stream_id, started_at DESC);
    END IF;
    IF pg_catalog.to_regclass('stream_sessions_one_live_uq') IS NULL THEN
        CREATE UNIQUE INDEX stream_sessions_one_live_uq ON stream_sessions (stream_id) WHERE state = 'live';
    END IF;
    IF pg_catalog.to_regclass('stream_sessions_ending_idx') IS NULL THEN
        CREATE INDEX stream_sessions_ending_idx ON stream_sessions (stream_id, ending_at) WHERE state = 'ending';
    END IF;
    IF pg_catalog.to_regclass('stream_sessions_freezing_idx') IS NULL THEN
        CREATE INDEX stream_sessions_freezing_idx ON stream_sessions (dvr_freezing_at) WHERE dvr_state = 'freezing';
    END IF;
END $do$;

-- 6-1 (a) 컬럼 2 — 되감기 목록의 단조 PDT 원천(playback_pdt)과 세션 귀속(session_id).
-- 위 playback_* 블록과 같은 이디엄: 카탈로그 선확인으로 ACCESS EXCLUSIVE 큐잉을 피한다.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema::name = pg_catalog.current_schema()
                     AND table_name::name  = 'stream_segments'
                     AND column_name::name = 'session_id') THEN
        ALTER TABLE stream_segments
            ADD COLUMN session_id   text,
            ADD COLUMN playback_pdt timestamptz;
    END IF;
END $do$;

-- 6-1 (b) 인덱스 2 (승인 표면 아님 — 통보에 병기).
-- rewind_cutoff_absent 감시가 max(start_wall_utc) 를 쓴다 — created_at 을 요청하지 않은 이유.
DO $do$
BEGIN
    IF pg_catalog.to_regclass('stream_segments_session_idx') IS NULL THEN
        CREATE INDEX stream_segments_session_idx ON stream_segments (stream_id, session_id, seq);
    END IF;
    IF pg_catalog.to_regclass('stream_segments_wall_idx') IS NULL THEN
        CREATE INDEX stream_segments_wall_idx ON stream_segments (stream_id, start_wall_utc DESC);
    END IF;
END $do$;

-- 6-1 (c) FK — NOT VALID 로 추가(기존 행 검증 생략). 새 행에는 강제되므로 session_id 에
-- '' 를 넣으면 23503 이다 — 세션 미확정은 반드시 SQL NULL(carrier 규약 5.4.2 · 게이트 n1c).
-- 검증(VALIDATE CONSTRAINT)은 EnsureSchema 밖 1회 운영 절차다.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'stream_segments'::regclass::oid
                     AND conname  = 'stream_segments_session_fk' AND contype = 'f') THEN
        ALTER TABLE stream_segments
            ADD CONSTRAINT stream_segments_session_fk
            FOREIGN KEY (session_id) REFERENCES stream_sessions(session_id) NOT VALID;
    END IF;
END $do$;

-- PDT 판 2 칸 일곱(POK-196 · 설계 r7 2.3 · 2.4 · 9.2 단계 2). timing_version = 그 행의 PDT 규약 판(NULL = 옛 식) ·
-- 나머지 여섯 = 생산자가 조각 파일에서 읽은 원래 사실과 이어 계산 회차다. 생산자 기록이라 소비자는 읽지 않는다(계약 B10 ·
-- 칸 뜻은 통지로 바꿀 수 있다). 출시 이미지는 이 칸을 모르고 INSERT 하므로 모두 NULL 허용 · 기본값 없음이다 — 쓰기자
-- 거부 트리거가 「timing_wall_guess NULL = 출시 이미지가 쓴 행」 으로 가른다.
-- 위 열 블록과 같은 카탈로그 확인 이디엄이되, 빠진 칸을 칸마다 고른 뒤 ALTER 한 번에 몰아 배타 잠금을 한 번만 잡는다.
-- 이 블록과 아래 CHECK · PDT 블록은 prod 에 적용한 뒤 같은 이름으로 정의를 고치지 않는다 — 「이름이 있으면 건너뜀」 이라
-- 재실행이 옛 정의를 조용히 남기고 기동 확인도 통과한다. 적용 뒤 바꿀 것은 새 이름 · 전진 SQL 로만 한다(설계 9.2).
DO $do$
DECLARE
    missing text[];
BEGIN
    SELECT pg_catalog.array_agg(pg_catalog.format('ADD COLUMN %I %s', c.name, c.type) ORDER BY c.ord)
      INTO missing
      FROM (VALUES (1, 'timing_version', 'smallint'), (2, 'timing_wall_guess', 'timestamptz'),
                   (3, 'timing_recorder_id', 'uuid'), (4, 'timing_segment_number', 'bigint'),
                   (5, 'timing_video_first_ns', 'bigint'), (6, 'timing_video_end_ns', 'bigint'),
                   (7, 'timing_round_id', 'text')) AS c(ord, name, type)
     WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema::name = pg_catalog.current_schema()
                          AND table_name::name  = 'stream_segments'
                          AND column_name::name = c.name::name);
    IF missing IS NOT NULL THEN
        EXECUTE 'ALTER TABLE stream_segments ' || pg_catalog.array_to_string(missing, ', ');
    END IF;
END $do$;

-- PDT CHECK 넷(설계 r7 2.3) — 원래 사실 칸의 NULL 짝을 강제한다. NOT VALID 로 더한다: 옛 행은 칸이 모두 NULL 이라 어차피
-- 만족하고, 검증 훑기의 배타 잠금을 피한다(새 행 · 갱신 행에는 강제된다). 위반은 23514 라 사이드카가 fateFatal 로 끝나므로
-- 생산자는 번호 넘침이면 표식 · 번호 · 영상 두 칸을 함께 NULL 로 쓴다(넷째 = 판 2 행은 w 와 귀속 무관 PDT 를 갖는다).
DO $do$
DECLARE
    missing text[];
BEGIN
    SELECT pg_catalog.array_agg(pg_catalog.format('ADD CONSTRAINT %I CHECK (%s) NOT VALID', c.name, c.expr) ORDER BY c.ord)
      INTO missing
      FROM (VALUES (1, 'stream_segments_timing_marker_chk',
                       '(timing_recorder_id IS NULL) = (timing_segment_number IS NULL)'),
                   (2, 'stream_segments_timing_video_chk',
                       '(timing_video_first_ns IS NULL) = (timing_video_end_ns IS NULL)'),
                   (3, 'stream_segments_timing_video_marker_chk',
                       'timing_video_first_ns IS NULL OR timing_recorder_id IS NOT NULL'),
                   (4, 'stream_segments_timing_v2_chk',
                       'timing_version IS NULL OR (timing_wall_guess IS NOT NULL AND playback_pdt IS NOT NULL)'))
           AS c(ord, name, expr)
     WHERE NOT EXISTS (SELECT 1 FROM pg_constraint
                        WHERE conrelid = 'stream_segments'::regclass::oid
                          AND conname  = c.name::name AND contype = 'c');
    IF missing IS NOT NULL THEN
        EXECUTE 'ALTER TABLE stream_segments ' || pg_catalog.array_to_string(missing, ', ');
    END IF;
END $do$;

-- 6-1 (d) 불변 트리거 — 발행 축 3열은 "NULL → 값 1회 채움"만 허용한다.
-- 값이 이미 있는데 바뀌면 이미 발행한 목록 줄이 실질 변경된다(설계 6.1.2).
-- PDT 칸(설계 r7 2.3 표 「불변」 열): 판 · 원래 사실 · 회차는 INSERT 뒤 어떤 변경도 거부한다(NULL → 값 포함 — 출시 이미지가 쓴
-- 행에 뒤늦게 사실을 채우면 그 행이 판 2 행처럼 읽힌다). 영상 끝만 꼬리 교정을 받는다 — pending 꼬리 ∧ 옛 값 있음 ∧ 늘림.
-- 옛 값이 NULL 인 꼬리는 교정이 이 칸을 넘기지 않는다(2.14). search_path 는 아래 하한 함수처럼 고정한다.
CREATE OR REPLACE FUNCTION stream_segments_immutable_axes() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $fn$
BEGIN
    IF OLD.session_id IS NOT NULL AND NEW.session_id IS DISTINCT FROM OLD.session_id THEN
        RAISE EXCEPTION 'session_id is immutable (stream=%, seq=%)', OLD.stream_id, OLD.seq;
    END IF;
    IF OLD.playback_pdt IS NOT NULL AND NEW.playback_pdt IS DISTINCT FROM OLD.playback_pdt THEN
        RAISE EXCEPTION 'playback_pdt is immutable (stream=%, seq=%)', OLD.stream_id, OLD.seq;
    END IF;
    IF OLD.playback_s3_key IS NOT NULL
       AND NEW.playback_s3_key IS DISTINCT FROM OLD.playback_s3_key THEN
        RAISE EXCEPTION 'playback_s3_key is immutable (stream=%, seq=%)', OLD.stream_id, OLD.seq;
    END IF;
    IF (NEW.timing_version, NEW.timing_wall_guess, NEW.timing_recorder_id, NEW.timing_segment_number,
        NEW.timing_video_first_ns, NEW.timing_round_id)
       IS DISTINCT FROM (OLD.timing_version, OLD.timing_wall_guess, OLD.timing_recorder_id, OLD.timing_segment_number,
                         OLD.timing_video_first_ns, OLD.timing_round_id) THEN
        RAISE EXCEPTION 'timing facts are immutable (stream=%, seq=%)', OLD.stream_id, OLD.seq;
    END IF;
    IF NEW.timing_video_end_ns IS DISTINCT FROM OLD.timing_video_end_ns
       AND NOT (OLD.upload_state = 'pending' AND OLD.timing_video_end_ns IS NOT NULL
                AND NEW.timing_video_end_ns IS NOT NULL AND NEW.timing_video_end_ns > OLD.timing_video_end_ns) THEN
        RAISE EXCEPTION 'timing_video_end_ns only grows on a pending tail (stream=%, seq=%)', OLD.stream_id, OLD.seq;
    END IF;
    RETURN NEW;
END $fn$;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = 'stream_segments'::regclass::oid
                     AND tgname  = 'stream_segments_immutable_axes_tg') THEN
        CREATE TRIGGER stream_segments_immutable_axes_tg
            BEFORE UPDATE ON stream_segments
            FOR EACH ROW EXECUTE FUNCTION stream_segments_immutable_axes();
    END IF;
END $do$;

-- 활성화 컷오프 — 설계 6.4 · 계약 6항 요구 9항 이행. 스트림별 UNIQUE(PK)·기존 승리는
-- 주조 CTE 의 ON CONFLICT DO NOTHING 이 담당한다. INSERT 경로는 store.go 의 CTE 하나뿐이고
-- UPDATE·DELETE 는 6.6 만료 동반 삭제(M6)와 장애 절차 수동 CAS 둘뿐이다(게이트 d2·d3).
CREATE TABLE IF NOT EXISTS stream_cutoffs (
    stream_id    text        PRIMARY KEY,
    cutoff_seq   bigint      NOT NULL,
    seeded_at    timestamptz NOT NULL DEFAULT pg_catalog.now(),
    seed_reason  text        NOT NULL,
    seed_channel text        NOT NULL,
    CONSTRAINT stream_cutoffs_seed_reason_chk  CHECK (seed_reason  IN ('live_ingress','state_obs')),
    CONSTRAINT stream_cutoffs_seed_channel_chk CHECK (seed_channel IN ('watcher','hook','scan','slate'))
);

-- 발행된 GAP 장부 — 설계 6.3. 배열이 아니라 표인 이유: 8시간 방송 최악에서 배열 재복사
-- 누적이 약 116.6MB 다. INSERT(recorded)가 Render·PUT 보다 먼저다(원자성 규약).
CREATE TABLE IF NOT EXISTS stream_published_gaps (
    stream_id text NOT NULL, seq bigint NOT NULL,
    session_id text NOT NULL REFERENCES stream_sessions(session_id),
    reason text NOT NULL,
    published_state text NOT NULL DEFAULT 'recorded',
    recorded_at timestamptz NOT NULL DEFAULT pg_catalog.now(), put_confirmed_at timestamptz,
    PRIMARY KEY (stream_id, seq),
    CONSTRAINT stream_published_gaps_reason_chk CHECK (reason IN ('upload_stall','slate','settle_deadline','mixed_boundary')),
    CONSTRAINT stream_published_gaps_state_chk  CHECK (published_state IN ('recorded','put_confirmed','vod_abandoned'))
);

-- ============================================================================
-- M6 장부(POK-196 M6-0) — 1번 소유(승인 표면 아님 · 계약-세그먼트인덱스 6절 통지). stream_segments 는 열을 바꾸지
-- 않고 트리거 둘만 더한다(ADR-0001 결정 2 의 승인 대상 아님).
-- ============================================================================

-- 회차 · 만료 · 의도적 종료 표식 열 넷. 위 6-1 (a) 와 같은 카탈로그 확인 이디엄이다 — 옛 볼륨에도 붙는다.
-- round_id = 그 회차 머리 세션의 session_id · round_closed_at = 회차 닫힘 · expired_at = 60일 만료의 조회 제외 ·
-- stop_marked_at = 의도적 종료(4D)가 멈춘 시각 표식. 열마다 따로 확인한다 — 하나만 빠진 DB 도 기동 스키마
-- 확인(MissingLedgerSchema)을 통과하는 상태로 되돌린다. ALTER 는 빠진 열이 있을 때만 돈다.
DO $do$
DECLARE
    col record;
BEGIN
    FOR col IN SELECT * FROM (VALUES ('round_id', 'text'), ('round_closed_at', 'timestamptz'),
                                     ('expired_at', 'timestamptz'), ('stop_marked_at', 'timestamptz')) AS c(name, type)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema::name = pg_catalog.current_schema()
                         AND table_name::name  = 'stream_sessions'
                         AND column_name::name = col.name::name) THEN
            EXECUTE pg_catalog.format('ALTER TABLE stream_sessions ADD COLUMN %I %s', col.name, col.type);
        END IF;
    END LOOP;
END $do$;

-- 열린 회차의 최신 세션을 고르는 회차 스캔(ListClosableRounds)의 부분 인덱스.
DO $do$
BEGIN
    IF pg_catalog.to_regclass('stream_sessions_round_open_idx') IS NULL THEN
        CREATE INDEX stream_sessions_round_open_idx ON stream_sessions (round_id, started_at DESC)
            WHERE round_closed_at IS NULL AND round_id IS NOT NULL;
    END IF;
END $do$;

-- seq 하한 — 스트림별로 다음 seq 가 내려가면 안 되는 값(불변식 4). 행을 지우면 아래 트리거가 올린다.
-- 사이드카는 읽기만 한다(LoadCursor 가 max(seq)+1 과 GREATEST).
CREATE TABLE IF NOT EXISTS stream_seq_floors (
    stream_id text        PRIMARY KEY,
    next_seq  bigint      NOT NULL,
    raised_at timestamptz NOT NULL DEFAULT pg_catalog.now()
);

-- 계약9 생명주기 이벤트 outbox — 장부 트랜잭션에 행을 넣고 발행기가 커밋된 행만 낸다(설계 15.5).
CREATE TABLE IF NOT EXISTS broadcast_lifecycle_outbox (
    event_id         text        PRIMARY KEY,
    round_id         text        NOT NULL,
    event_type       text        NOT NULL,
    sequence         bigint      NOT NULL,
    occurred_at      timestamptz NOT NULL,
    ingest_stream_id text        NOT NULL,
    streamer_id      text,
    payload          jsonb       NOT NULL DEFAULT '{}',
    state            text        NOT NULL DEFAULT 'pending',
    attempts         int         NOT NULL DEFAULT 0,
    next_at          timestamptz NOT NULL DEFAULT pg_catalog.now(),
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT pg_catalog.now(),
    published_at     timestamptz,
    CONSTRAINT broadcast_lifecycle_outbox_state_chk CHECK (state IN ('pending','published','abandoned')),
    UNIQUE (round_id, sequence)
);
DO $do$
BEGIN
    IF pg_catalog.to_regclass('broadcast_lifecycle_outbox_due_idx') IS NULL THEN
        CREATE INDEX broadcast_lifecycle_outbox_due_idx ON broadcast_lifecycle_outbox (next_at) WHERE state = 'pending';
    END IF;
END $do$;

-- FK 열 인덱스 셋(승인 표면 아님 — 통보에 병기). 세션 행을 지우는 쪽(만료 · 후속 계승 끊기)의 FK 검사가 참조 표를
-- session_id · inherits_session 으로 찾는다. 앞의 (stream_id, session_id, seq) 인덱스는 session_id 로 시작하지 않아 그
-- 검사가 탈 수 없다 — 없으면 세션마다 참조 표를 처음부터 훑는다.
DO $do$
BEGIN
    IF pg_catalog.to_regclass('stream_segments_session_id_idx') IS NULL THEN
        CREATE INDEX stream_segments_session_id_idx ON stream_segments (session_id) WHERE session_id IS NOT NULL;
    END IF;
    IF pg_catalog.to_regclass('stream_published_gaps_session_idx') IS NULL THEN
        CREATE INDEX stream_published_gaps_session_idx ON stream_published_gaps (session_id);
    END IF;
    IF pg_catalog.to_regclass('stream_sessions_inherits_idx') IS NULL THEN
        CREATE INDEX stream_sessions_inherits_idx ON stream_sessions (inherits_session) WHERE inherits_session IS NOT NULL;
    END IF;
END $do$;

-- 정산 스캔의 발행 흔적 판독 인덱스(M6-3b). 스캔은 30초마다 ending 세션마다 그 세션 조각에 ③ uploaded 가 하나라도 있는지
-- 본다. (stream_id, session_id, seq) 인덱스로는 uploaded 가 없는 세션의 조각을 모두 읽는다 — uploaded 행만 담는 부분
-- 인덱스로 탐색 한 번이 된다. 대가: 술어 열 playback_upload_state 를 담아 ③ 확정 갱신이 HOT 를 잃는다(PG17 실측
-- HOT 62.7% → 15.4% · 정확성 영향 없음 — db ④c M1). 이 열을 담는 인덱스면 어느 꼴이든 같다.
DO $do$
BEGIN
    IF pg_catalog.to_regclass('stream_segments_uploaded_session_idx') IS NULL THEN
        CREATE INDEX stream_segments_uploaded_session_idx ON stream_segments (stream_id, session_id)
            WHERE playback_upload_state = 'uploaded';
    END IF;
END $do$;

-- 하한 갱신 — 지워진 행의 스트림별 max(seq)+1 로 올린다(내리지 않는다). 지우는 쪽이 누구든(만료 · clip 탈퇴 정리 ·
-- 수동) 같은 트랜잭션에서 돈다. 실패하면 그 DELETE 도 롤백된다. 하한 행을 잠그므로 같은 스트림의 동시 DELETE 는
-- 서로 직렬화된다. 한 문장 안에서는 스트림 순으로 잠가 여러 스트림을 지우는 두 DELETE 문장이 교착하지 않는다 —
-- 여러 DELETE 문장을 한 트랜잭션에 엮어 스트림을 서로 다른 순서로 지우면 교착할 수 있다.
-- SECURITY DEFINER — 지우는 계정(app_clip 등)은 하한 표 권한이 없다. 정의자 함수는 부른 쪽의 search_path 로 돌면
-- public 에 심은 덫을 정의자 신분으로 부르므로 pg_catalog · pg_temp 로 고정하고 표 이름은 public 으로 한정한다.
-- 다른 표에 붙으면 멈춘다 — 그 표의 (아무 스트림, 아주 큰 seq) 행을 지워 남의 하한을 올리는 길이다. 새 부착은 아래
-- EXECUTE 회수가 막고(CREATE TRIGGER 가 EXECUTE 를 본다), 이 검사는 이미 붙은 것을 막는 겹 방어다.
CREATE OR REPLACE FUNCTION stream_segments_raise_seq_floor() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $fn$
BEGIN
    IF TG_RELID <> 'public.stream_segments'::regclass::oid THEN
        RAISE EXCEPTION 'seq_floor_raise_foreign_table %.%', TG_TABLE_SCHEMA, TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    INSERT INTO public.stream_seq_floors AS f (stream_id, next_seq)
    SELECT d.stream_id, pg_catalog.max(d.seq) + 1::bigint
      FROM deleted_rows d
     GROUP BY d.stream_id
     ORDER BY d.stream_id
    ON CONFLICT (stream_id) DO UPDATE
       SET next_seq = EXCLUDED.next_seq, raised_at = pg_catalog.now()
     WHERE f.next_seq < EXCLUDED.next_seq;
    RETURN NULL;
END $fn$;
-- 트리거 발화는 EXECUTE 를 보지 않으므로 지우는 계정(app_clip 등)은 그대로 하한을 올린다. 다시 돌려도 같다(멱등).
REVOKE EXECUTE ON FUNCTION stream_segments_raise_seq_floor() FROM PUBLIC;

-- 하한 검사 — 하한 아래 seq 의 INSERT 를 거부한다(23514 seq_below_floor). 하한을 모르는 옛 판 사이드카가 seq 를
-- 다시 쓰면 같은 영구 URL 에 다른 조각이 실리므로 그 INSERT 를 막는다(fail-closed). 막힌 결과는 그 스트림에 그치지
-- 않는다 — 사이드카는 23 클래스를 복구 불가(classifyInsertError fateFatal)로 보고 프로세스를 종료 코드 1 로 끝내므로
-- 상자의 다른 스트림 색인도 함께 끊긴다. 엔진이 다시 띄워도(restart: on-failure) 같은 seq 에서 또 걸려 재기동을
-- 되풀이한다. 새 판은 LoadCursor 가 하한 아래를 내지 않아 여기 걸리지 않는다 — 걸리면 결함이다. 넣는 계정(media_indexer)은 하한 표
-- SELECT 를 가지므로 부른 쪽 권한으로 돈다. 하한 행은 잠그지 않고 읽는다 — 하한을 올리는 DELETE 와 겹친 INSERT 는
-- 커밋 전 하한을 못 볼 수 있지만, 사이드카는 LoadCursor 가 정한 하한 위 seq 만 내므로 그 경합의 실경로는 없다.
CREATE OR REPLACE FUNCTION stream_segments_check_seq_floor() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $fn$
DECLARE
    floor_seq bigint;
BEGIN
    SELECT f.next_seq INTO floor_seq FROM public.stream_seq_floors f WHERE f.stream_id = NEW.stream_id;
    IF floor_seq IS NOT NULL AND NEW.seq < floor_seq THEN
        RAISE EXCEPTION 'seq_below_floor'
            USING ERRCODE = 'check_violation',
                  DETAIL = pg_catalog.format('stream=%s seq=%s floor=%s', NEW.stream_id, NEW.seq, floor_seq);
    END IF;
    RETURN NEW;
END $fn$;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = 'stream_segments'::regclass::oid
                     AND tgname  = 'stream_segments_seq_floor_raise') THEN
        CREATE TRIGGER stream_segments_seq_floor_raise
            AFTER DELETE ON stream_segments
            REFERENCING OLD TABLE AS deleted_rows
            FOR EACH STATEMENT EXECUTE FUNCTION stream_segments_raise_seq_floor();
    END IF;
END $do$;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = 'stream_segments'::regclass::oid
                     AND tgname  = 'stream_segments_seq_floor_guard') THEN
        CREATE TRIGGER stream_segments_seq_floor_guard
            BEFORE INSERT ON stream_segments
            FOR EACH ROW EXECUTE FUNCTION stream_segments_check_seq_floor();
    END IF;
END $do$;

-- /dvr 하한 원장(POK-525) — 열린 회차의 하한 정본. KVS 는 사본이다. 회차가 닫히면 해제 뒤 행을 지운다.
CREATE TABLE IF NOT EXISTS stream_edge_bounds (
    stream_id   text        NOT NULL,
    round_id    text        NOT NULL,
    dvr_floor   bigint      NOT NULL CHECK (dvr_floor >= 1),
    released_at timestamptz,
    updated_at  timestamptz NOT NULL DEFAULT pg_catalog.now(),
    PRIMARY KEY (stream_id, round_id)
);

-- ============================================================================
-- PDT 판 게이트 · 되돌리기 거부(POK-196 · 설계 r7 9.3 · 9.4) — 1번 소유 표(승인 표면 아님 · 통지). 게이트 표가 쓰기자
-- 거부 트리거보다 앞이다 — 그 트리거가 이 표를 읽는다. 위 칸 블록의 「prod 적용 뒤 같은 이름으로 정의를 고치지 않는다」 가
-- 이 블록에도 그대로다(함수 본문만 CREATE OR REPLACE 라 재실행이 바꾼다).
-- ============================================================================

-- 게이트 한 행 — new_round_version = 스위치(새 회차를 여는 판 · 기본 NULL) · highest_version = 래치(스위치가 2 가 된 적이
-- 있으면 2). 사이드카(media_indexer)는 SELECT 만 갖고 잠그지 않는다 — SELECT … FOR SHARE 는 그 표의 UPDATE 권한도 요구하므로
-- (PostgreSQL 17 SELECT 문서 The Locking Clause — https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE)
-- 직렬화는 행 잠금이 아니라 advisory 열쇠 pc_timing_gate 로 한다(D14). 회차 개시는 공유 잠금을 잡고 읽고, 아래 게이트
-- 트리거는 배타 잠금을 잡는다. 스위치 · 래치는 마스터가 SET ROLE media_owner 로 손 SQL 로만 바꾼다.
CREATE TABLE IF NOT EXISTS stream_timing_gate (
    id                boolean     PRIMARY KEY DEFAULT true,
    new_round_version smallint,
    highest_version   smallint,
    changed_at        timestamptz,
    changed_by        text,
    CONSTRAINT stream_timing_gate_one_row_chk CHECK (id),
    CONSTRAINT stream_timing_gate_switch_chk  CHECK (new_round_version IN (2)),
    CONSTRAINT stream_timing_gate_latch_chk   CHECK (highest_version IN (2))
);

-- 씨앗 행(스위치 · 래치 NULL). 행이 있으면 INSERT 문장을 돌리지 않는다 — 수렴한 재실행이 게이트 표를 잠그지 않게.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM stream_timing_gate) THEN
        INSERT INTO stream_timing_gate (id) VALUES (true) ON CONFLICT (id) DO NOTHING;
    END IF;
END $do$;

-- 게이트 트리거 — 맨 앞에서 열쇠 pc_timing_gate 의 배타 잠금을 잡아 진행 중인 회차 개시(공유 잠금)가 끝나기를 기다린다.
-- DELETE 는 거부한다. 스위치가 판을 가리키면 래치를 같이 세운다. 래치 낮추기(→ NULL)는 스위치 NULL 이고 판 2 행이 0 일
-- 때만이다(MongoDB CannotDowngrade 꼴) — 판 2 행 셈은 잠금 뒤라 그 사이 커밋한 개시의 행까지 센다. 스위치를 NULL 로 내리는
-- 것은 늘 된다(판 2 쓰기를 멈추는 길 · 열린 판 2 회차는 판 고정으로 끝까지 판 2). 거부는 23514 다.
-- 낮추기는 READ COMMITTED 에서만 받는다 — REPEATABLE READ · SERIALIZABLE 은 스냅숏을 잠금 대기 앞에 잡아, 기다리는 동안
-- 커밋된 판 2 행을 셈에서 놓친다(c1 · c4b 보안 M1 실측). 그 격리의 낮추기는 판 2 행과 무관하게 거부한다.
-- hashtext 는 integer 를 내고 pg_catalog 의 잠금 함수는 bigint 판뿐이라 ::bigint 로 맞춘다(store.go 의 컷오프 열쇠와 같은 꼴).
CREATE OR REPLACE FUNCTION stream_timing_gate_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $fn$
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('pc_timing_gate'::text)::bigint);
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'timing_gate_delete_refused' USING ERRCODE = 'check_violation';
    END IF;
    NEW.highest_version := GREATEST(NEW.highest_version, NEW.new_round_version);
    IF OLD.highest_version IS NOT NULL AND NEW.highest_version IS NULL
       AND pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'timing_latch_lower_needs_read_committed'
            USING ERRCODE = 'check_violation',
                  DETAIL = pg_catalog.format('transaction_isolation=%s', pg_catalog.current_setting('transaction_isolation'));
    END IF;
    IF OLD.highest_version IS NOT NULL AND NEW.highest_version IS NULL
       AND EXISTS (SELECT 1 FROM public.stream_segments s WHERE s.timing_version = 2) THEN
        RAISE EXCEPTION 'timing_latch_lower_refused'
            USING ERRCODE = 'check_violation', DETAIL = 'stream_segments 에 판 2 행이 있다';
    END IF;
    RETURN NEW;
END $fn$;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = 'stream_timing_gate'::regclass::oid
                     AND tgname  = 'stream_timing_gate_guard_tg') THEN
        CREATE TRIGGER stream_timing_gate_guard_tg
            BEFORE UPDATE OR DELETE ON stream_timing_gate
            FOR EACH ROW EXECUTE FUNCTION stream_timing_gate_guard();
    END IF;
END $do$;

-- 쓰기자 거부(설계 9.4 — 기동 전 검사의 뒷받침) — 래치가 선 뒤 timing_wall_guess 없는 INSERT(출시 이미지)를 23514
-- legacy_writer_refused 로 거부한다. 새 이미지는 판과 무관하게 이 칸을 늘 채우므로 걸리지 않는다. 결과는 하한 검사와 같다 —
-- 23 클래스라 출시 사이드카가 종료 코드 1 로 끝나 상자의 모든 스트림 색인이 멈추고 재기동을 되풀이하지만, 장부에 판 2 를
-- 모르는 줄은 들어가지 않는다. 하한 검사처럼 부른 쪽 권한으로 게이트를 읽는다(넣는 계정 media_indexer 는 게이트 SELECT 를
-- 갖는다). 래치 판정을 「NULL 이 아님」 으로 두는 까닭: 래치는 판 2 이상이 쓰인 적이 있다는 뜻이고, 어느 판이든 옛 식 쓰기는
-- 그 회차를 깬다.
CREATE OR REPLACE FUNCTION stream_segments_refuse_legacy_writer() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $fn$
BEGIN
    IF NEW.timing_wall_guess IS NULL
       AND EXISTS (SELECT 1 FROM public.stream_timing_gate g WHERE g.highest_version IS NOT NULL) THEN
        RAISE EXCEPTION 'legacy_writer_refused'
            USING ERRCODE = 'check_violation',
                  DETAIL = pg_catalog.format('stream=%s seq=%s', NEW.stream_id, NEW.seq);
    END IF;
    RETURN NEW;
END $fn$;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = 'stream_segments'::regclass::oid
                     AND tgname  = 'stream_segments_legacy_writer_guard') THEN
        CREATE TRIGGER stream_segments_legacy_writer_guard
            BEFORE INSERT ON stream_segments
            FOR EACH ROW EXECUTE FUNCTION stream_segments_refuse_legacy_writer();
    END IF;
END $do$;

-- PDT 부분 인덱스 넷 — 비동시(CONCURRENTLY 아님 · 트랜잭션 · DO 블록 안에서 돈다)라 처음 만드는 동안 그 표 쓰기가 멈춘다
-- (설계 9.2 단계 2 (ㄱ) · 방송 없는 창). 회차 인덱스는 생산자가 p(같은 회차 최신 행)를 한 줄로 찾는다(설계 2.4). floor
-- 인덱스 둘은 채팅 시각 → 영상 위치 조회가 한쪽 판만 있는 스트림에서 다른 판 행을 끝까지 훑지 않게 한다(r6 2.12) — 판 2
-- 인덱스는 게이트 트리거의 「판 2 행이 있나」 도 탄다. 판 2 PDT 인덱스는 채팅 수집기 판 2 바닥 조회 앞의 존재 확인
-- (playback_pdt ≤ t 인 판 2 행이 있는가)이 이른 t 에서 판 2 행을 끝까지 훑지 않게 한다 — 답(seq 최대)은 v2_seq_idx 로
-- 고른다(판 2 PDT 는 seq 순으로 단조가 아니라 pdt 순 조회는 답이 다르다).
DO $do$
BEGIN
    IF pg_catalog.to_regclass('stream_segments_timing_round_idx') IS NULL THEN
        CREATE INDEX stream_segments_timing_round_idx ON stream_segments (stream_id, timing_round_id, seq)
            WHERE timing_round_id IS NOT NULL;
    END IF;
    IF pg_catalog.to_regclass('stream_segments_v2_seq_idx') IS NULL THEN
        CREATE INDEX stream_segments_v2_seq_idx ON stream_segments (stream_id, seq) WHERE timing_version = 2;
    END IF;
    IF pg_catalog.to_regclass('stream_segments_legacy_wall_idx') IS NULL THEN
        CREATE INDEX stream_segments_legacy_wall_idx ON stream_segments (stream_id, start_wall_utc DESC)
            WHERE timing_version IS NULL;
    END IF;
    IF pg_catalog.to_regclass('stream_segments_v2_pdt_idx') IS NULL THEN
        CREATE INDEX stream_segments_v2_pdt_idx ON stream_segments (stream_id, playback_pdt DESC)
            WHERE timing_version = 2;
    END IF;
END $do$;
`

// roundFrozenDDL 은 회차 불변 트리거다(POK-525 설계 r9.3 2-2). 운영 SQL 2(infra/terraform/prod/runtime/sql/
// 60_round_frozen.sql)의 두 표지 사이가 이 글자와 바이트가 같다 — prod_sql_contract_test.go 가 강제한다.
//
// schemaDDL(운영 SQL 1 의 사본)과 따로 두는 이유: 운영 SQL 1 은 교체 전에 다시 돌므로, 거기 넣으면 옛 판(A)으로 돌아갈 수
// 있는 구간에 트리거가 깔려 A 의 늦은 4D 분리가 거부되고 그 4D 는 유실된다(K5). 운영은 새 꼴 쓰기를 켠 뒤(contract)
// 운영 SQL 2 로만 깐다. 로컬 · 시험에는 옛 판이 없으므로 EnsureSchema 가 schemaDDL 뒤에 함께 돌린다. 기동 필수 목록
// (MissingLedgerSchema)에는 넣지 않는다 — 새 판은 트리거보다 먼저 떠야 하고, 정합은 round_id 를 쓰지 않는 코드가
// 지킨다(round_frozen_static_test.go). 트리거는 다른 길(옛 이미지 · 손 SQL · 앞날 코드)을 막는 울타리다.
const roundFrozenDDL = `
-- 회차 불변 — stream_sessions.round_id 를 다른 값으로 바꾸는 UPDATE 를 거부한다(23514 · NULL 에서 값 · 값에서 NULL 도
-- 바꾸는 것이다). 같은 값을 다시 쓰거나 다른 열만 바꾸는 UPDATE 는 트리거가 돌지 않는다(UPDATE OF round_id + WHEN) —
-- 그래서 함수는 따지지 않고 거부한다. 하한 검사(seq_below_floor)와 같은 꼴로 search_path 를 고정한다.
CREATE OR REPLACE FUNCTION stream_sessions_reject_round_change() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $fn$
BEGIN
    RAISE EXCEPTION 'stream_sessions.round_id is frozen'
        USING ERRCODE = 'check_violation',
              DETAIL = pg_catalog.format('session=%s old=%s new=%s', OLD.session_id, OLD.round_id, NEW.round_id);
END $fn$;

-- 이름이 없을 때만 만든다 — 수렴한 재실행은 stream_sessions 를 잠그지 않는다(CREATE TRIGGER 는 SHARE ROW EXCLUSIVE 로
-- 그 표의 쓰기를 잠깐 막는다). 같은 이름의 다른 모양 트리거가 이미 있으면 건너뛰므로, 운영 SQL 2 는 끝에서 모양을 단언한다.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = 'stream_sessions'::regclass::oid
                     AND tgname  = 'stream_sessions_round_frozen') THEN
        CREATE TRIGGER stream_sessions_round_frozen
            BEFORE UPDATE OF round_id ON stream_sessions
            FOR EACH ROW
            WHEN (OLD.round_id IS DISTINCT FROM NEW.round_id)
            EXECUTE FUNCTION stream_sessions_reject_round_change();
    END IF;
END $do$;
`

// EnsureSchema 는 표가 없으면 만든다. 회차 불변 트리거(roundFrozenDDL)도 schemaDDL 뒤에 같은 트랜잭션으로 깐다 —
// 질의 하나에 이어 보내므로 판정 표가 보이는 순간 트리거도 있다.
//
// Store 인터페이스 밖의 자유 함수인 이유(D7, G7): 스키마 부트스트랩은 영속 계약(Store)과
// 변경 이유가 다르다(SRP). 나중에 정식 마이그레이션 도구를 도입하게 되면 이 파일만 교체하면 되고,
// 인터페이스와 테스트용 가짜 구현은 하나도 안 바뀐다.
func EnsureSchema(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, schemaDDL+roundFrozenDDL); err != nil {
		return fmt.Errorf("stream_segments 스키마 생성 실패: %w", err)
	}
	return nil
}
