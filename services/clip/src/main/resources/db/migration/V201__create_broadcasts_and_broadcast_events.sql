-- 방송 한 회당 한 줄. 계약9 생명주기 이벤트(ADR-016)가 이 표를 만든다.
-- streamer_id에 FK를 걸지 않는다 — 그 표는 auth 소유이고, 서로의 표를 직접
-- 참조하지 않는 것이 ADR-022의 경계다. FK는 읽기보다 강한 결합이다.
CREATE TABLE broadcasts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 영상 서버가 부르는 방송 이름. UNIQUE가 동시 생성을 막는 방어선이다.
    stream_id      VARCHAR(128) NOT NULL,
    streamer_id    VARCHAR(128) NOT NULL,
    -- live · ended · vod_ready. vod_ready는 이번 범위에서 쓰지 않지만
    -- 10_데이터플로우가 정한 값 집합이라 제약에 미리 넣는다.
    status         VARCHAR(16)  NOT NULL,
    -- 종료가 먼저 도착하면 시작 시각을 모른 채 행이 생긴다(ADR-016 placeholder).
    -- 그래서 NOT NULL을 걸 수 없다. NULL이 곧 "역순으로 도착했다"는 표시다.
    started_at     TIMESTAMPTZ,
    ended_at       TIMESTAMPTZ,
    -- broadcast.started payload의 trackManifest 스냅샷. 구조가 1번 쪽에서
    -- 바뀌어도 안 깨지도록 통째로 담는다.
    track_manifest JSONB,
    -- 마지막으로 반영한 이벤트 순서 번호. 이보다 낮은 번호가 뒤늦게 오면
    -- 무시한다(POK-88). 이 칸이 없으면 역순 판정 자체가 불가능하다.
    last_sequence  BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_broadcasts_stream_id UNIQUE (stream_id),
    CONSTRAINT ck_broadcasts_status CHECK (status IN ('live', 'ended', 'vod_ready'))
);

-- 받은 편지 기록. event_id UNIQUE가 멱등의 진짜 방어선이다(POK-87) —
-- 조회 후 삽입은 동시 요청에 뚫린다(auth PairingAttemptRecorder 선례).
CREATE TABLE broadcast_events (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     VARCHAR(128) NOT NULL,
    -- 명부의 id가 아니라 봉투에 적힌 stream_id를 적는다. 처리에 실패해 명부
    -- 줄이 안 생긴 편지도 남아야 나중에 추적할 수 있다.
    stream_id    VARCHAR(128) NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    -- sequence는 SQL 예약어다. 컬럼 이름에 쓰면 따옴표가 필요해진다.
    sequence_no  BIGINT       NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_broadcast_events_event_id UNIQUE (event_id)
);

-- "이 방송에 온 편지를 다 보여줘" — 감사·디버깅용.
CREATE INDEX idx_broadcast_events_stream ON broadcast_events (stream_id, sequence_no);

COMMENT ON COLUMN broadcasts.started_at IS '종료 선도착 placeholder는 NULL이다';
COMMENT ON COLUMN broadcasts.last_sequence IS '역순 도착 판정 기준(POK-88)';
