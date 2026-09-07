-- 방송 정보(POK-234). 제목·태그·카테고리·시청자 수를 <b>시점마다 한 줄</b>로 쌓는다.
-- 한 줄을 갱신하지 않는 이유는 시청자 수가 화면에서 <b>추이</b>로 쓰이기 때문이다 —
-- 마지막 값만 두면 「몇 명이었나」는 알아도 「언제 몰렸나」를 못 그린다.
CREATE TABLE broadcast_info (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stream_id        VARCHAR(128) NOT NULL,
    channel_id       TEXT NOT NULL,
    observed_at      TIMESTAMPTZ NOT NULL,
    live_title       TEXT,
    tags             TEXT[] NOT NULL DEFAULT '{}',
    category         TEXT,
    concurrent_users INT
);

COMMENT ON TABLE broadcast_info IS '치지직 방송 정보 관측 이력. 시점마다 한 줄이고 갱신하지 않는다';
COMMENT ON COLUMN broadcast_info.observed_at IS '우리가 물어본 시각. 치지직은 이 값을 안 주므로 우리 시계다(chat_donations.received_at과 같은 축)';
COMMENT ON COLUMN broadcast_info.tags IS '빈 목록과 「모른다」를 안 가른다 — 치지직이 태그 없음을 빈 배열로 준다';
COMMENT ON COLUMN broadcast_info.concurrent_users IS '동시 시청자 수. 목록 훑기에서 그 방송을 못 찾으면 NULL';

-- 창구가 타는 색인. 최신 한 줄(DESC LIMIT 1)과 구간 훑기가 같은 열쇠를 쓴다.
CREATE INDEX idx_broadcast_info_stream_observed ON broadcast_info (stream_id, observed_at);
