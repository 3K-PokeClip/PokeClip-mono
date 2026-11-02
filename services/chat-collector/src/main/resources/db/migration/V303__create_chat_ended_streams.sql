-- 종료 편지가 시작보다 먼저 왔을 때 남기는 일회용 메모(ADR-016의 ENDED tombstone).
-- ADR은 Redis를 지정하지만 DB에 둔다 — 근거는 PRD 「결정 사항」. 1번과 협의 대상이다.
--
-- clip의 broadcasts 표와 이름이 겹치지 않게 chat_ 접두어를 쓴다. DB를 네 서버가
-- 공유하므로 접두어 없이는 남의 표를 자기 것으로 착각한다. clip의 표는 읽지 않는다
-- (ADR-022의 경계).
CREATE TABLE chat_ended_streams (
    -- 계약9의 streamId. clip의 broadcasts.stream_id와 같은 폭이어야 한다.
    stream_id     VARCHAR(128) PRIMARY KEY,
    -- 이 방송에서 마지막으로 반영한 순서 번호. 이보다 낮거나 같은 시작은 무시한다
    -- (ADR-016: "이후 더 낮은 sequence의 started는 무시한다").
    last_sequence BIGINT      NOT NULL,
    -- 편지에 적힌 종료 시각.
    ended_at      TIMESTAMPTZ NOT NULL,
    -- 메모를 남긴 시각. 치우기의 기준이다(TTL 24h를 이 값으로 잰다).
    created_at    TIMESTAMPTZ NOT NULL
);

-- PK가 stream_id라 치우기 조회가 전체 훑기가 된다. 지금은 표가 작지만 값이 싸다.
CREATE INDEX idx_chat_ended_streams_created ON chat_ended_streams (created_at);
