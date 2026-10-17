-- 방송 번호 → 물리 키(POK-233, 계약9 ingestStreamId). 편지의 streamId가 방송마다 새 회차 번호가 되면 조각 장부(stream_segments)의
-- stream_id(인제스트 경로, 오늘은 스트림키)와 갈린다. 영상 시점 조회는 판별기가 방송 번호로 부르므로 이 표로 물리 키를 찾는다.
-- 줄이 없으면(물리 키를 안 실은 옛 편지) 방송 번호가 곧 물리 키다. 한 번 적으면 안 바꾼다(한 회차의 편지는 같은 경로를 싣는다).
CREATE TABLE chat_ingest_keys (
    stream_id        VARCHAR(128) PRIMARY KEY,
    ingest_stream_id VARCHAR(128) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
