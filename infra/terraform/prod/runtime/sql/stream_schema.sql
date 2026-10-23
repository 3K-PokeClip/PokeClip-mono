-- stream_* 스키마 — media/internal/index/ddl.go 의 schemaDDL 사본이다(ADR-0001 추기 2026-10-23).
--
-- 아래 두 표지 사이는 schemaDDL 과 바이트가 같아야 한다. media 의 prod_sql_contract_test.go 가
-- 강제하고, 어긋나면 media-ci 가 깨진다. ddl.go 를 바꾸면 표지 사이를 schemaDDL 그대로 다시
-- 붙여 넣는다(컬럼 변경은 3번 승인 — ADR-0001 결정 2).
--
-- 혼자 돌리지 않는다. 20_schema.sql 이 media_owner 로 바꾼 뒤 \ir 로 읽는다 — 그래야 표 · 함수의
-- 소유자가 media_owner 다.
-- BEGIN schemaDDL
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
CREATE INDEX IF NOT EXISTS stream_sessions_stream_idx ON stream_sessions (stream_id, started_at DESC);
-- state='live' 만 보는 이유: state <> 'ended' 로 두면 ending 세션이 새 세션 INSERT 를 막아
-- 방송 중 되감기를 새로 못 연다(설계 6.2).
CREATE UNIQUE INDEX IF NOT EXISTS stream_sessions_one_live_uq
  ON stream_sessions (stream_id) WHERE state = 'live';
CREATE INDEX IF NOT EXISTS stream_sessions_ending_idx
  ON stream_sessions (stream_id, ending_at) WHERE state = 'ending';
CREATE INDEX IF NOT EXISTS stream_sessions_freezing_idx
  ON stream_sessions (dvr_freezing_at) WHERE dvr_state = 'freezing';

-- 6-1 (a) 컬럼 2 — 되감기 목록의 단조 PDT 원천(playback_pdt)과 세션 귀속(session_id).
-- 위 playback_* 블록과 같은 이디엄: 카탈로그 선확인으로 ACCESS EXCLUSIVE 큐잉을 피한다.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name  = 'stream_segments'
                     AND column_name = 'session_id') THEN
        ALTER TABLE stream_segments
            ADD COLUMN session_id   text,
            ADD COLUMN playback_pdt timestamptz;
    END IF;
END $do$;

-- 6-1 (b) 인덱스 2 (승인 표면 아님 — 통보에 병기).
CREATE INDEX IF NOT EXISTS stream_segments_session_idx
  ON stream_segments (stream_id, session_id, seq);
-- rewind_cutoff_absent 감시가 max(start_wall_utc) 를 쓴다 — created_at 을 요청하지 않은 이유.
CREATE INDEX IF NOT EXISTS stream_segments_wall_idx
  ON stream_segments (stream_id, start_wall_utc DESC);

-- 6-1 (c) FK — NOT VALID 로 추가(기존 행 검증 생략). 새 행에는 강제되므로 session_id 에
-- '' 를 넣으면 23503 이다 — 세션 미확정은 반드시 SQL NULL(carrier 규약 5.4.2 · 게이트 n1c).
-- 검증(VALIDATE CONSTRAINT)은 EnsureSchema 밖 1회 운영 절차다.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'stream_segments'::regclass
                     AND conname  = 'stream_segments_session_fk' AND contype = 'f') THEN
        ALTER TABLE stream_segments
            ADD CONSTRAINT stream_segments_session_fk
            FOREIGN KEY (session_id) REFERENCES stream_sessions(session_id) NOT VALID;
    END IF;
END $do$;

-- 6-1 (d) 불변 트리거 — 발행 축 3열은 "NULL → 값 1회 채움"만 허용한다.
-- 값이 이미 있는데 바뀌면 이미 발행한 목록 줄이 실질 변경된다(설계 6.1.2).
CREATE OR REPLACE FUNCTION stream_segments_immutable_axes() RETURNS trigger AS $fn$
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
    RETURN NEW;
END $fn$ LANGUAGE plpgsql;

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = 'stream_segments'::regclass
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
    seeded_at    timestamptz NOT NULL DEFAULT now(),
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
    recorded_at timestamptz NOT NULL DEFAULT now(), put_confirmed_at timestamptz,
    PRIMARY KEY (stream_id, seq),
    CONSTRAINT stream_published_gaps_reason_chk CHECK (reason IN ('upload_stall','slate','settle_deadline','mixed_boundary')),
    CONSTRAINT stream_published_gaps_state_chk  CHECK (published_state IN ('recorded','put_confirmed','vod_abandoned'))
);
-- END schemaDDL
