-- 후원 이벤트. 채팅과 표를 나눈 이유는 칸이 거의 안 겹치고(금액·후원 종류·후원 문구)
-- 지문 UNIQUE 규칙도 다르기 때문이다.
CREATE TABLE chat_donations (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stream_id          VARCHAR(128) NOT NULL,
    channel_id         TEXT NOT NULL,
    donator_channel_id TEXT NOT NULL,
    donator_nickname   TEXT,
    donation_type      VARCHAR(16) NOT NULL,
    pay_amount         BIGINT,
    donation_text      TEXT NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE chat_donations IS '치지직 후원 이벤트. 치지직이 시각을 안 주므로 received_at(우리 시각)만 있다. 지문 제약 없음 — 세션이 한 번만 준다';
COMMENT ON COLUMN chat_donations.received_at IS '우리가 받은 시각. chat_messages.message_time(치지직 시계)과 축이 다르다';
COMMENT ON COLUMN chat_donations.pay_amount IS '원. 치지직이 문자열로 주며 숫자로 못 읽으면 NULL';

-- 범위 창구(POK-234)가 타는 색인. 후원의 시각 축은 received_at 하나뿐이다.
CREATE INDEX idx_chat_donations_stream_received ON chat_donations (stream_id, received_at);
