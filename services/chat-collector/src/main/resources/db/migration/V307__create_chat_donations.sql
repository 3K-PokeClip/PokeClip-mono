-- 후원 이벤트. 채팅과 표를 나눈 이유는 칸이 거의 안 겹치고(금액·후원 종류·후원 문구)
-- 지문 UNIQUE 규칙도 다르기 때문이다.
CREATE TABLE chat_donations (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stream_id          VARCHAR(128) NOT NULL,
    channel_id         TEXT NOT NULL,
    donator_channel_id TEXT NOT NULL,
    donator_nickname   TEXT,
    -- 🔴 TEXT다. VARCHAR(16)이었는데 그 길이가 「치지직이 주는 종류는 짧다」는 가정에만
    -- 서 있었고, 넘치면 실 PG가 value too long 으로 거부한다. 후원 저장에는 「나쁜 한 건만
    -- 버리는」 격리가 없어 배치가 1초마다 영원히 재시도되고 그 방송의 후원이 통째로 멎는다.
    -- 길이를 지켜 얻는 것이 없고(열거값이라 어차피 짧다) 잃는 것이 그것이라 제한을 뺐다.
    donation_type      TEXT NOT NULL,
    pay_amount         BIGINT,
    donation_text      TEXT NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL,

    -- 후원 내용 지문. 채팅의 content_sha256과 같은 이유로 원문 대신 해시다 —
    -- btree 인덱스 행 크기 제한(~2704B) 때문에 긴 후원 문구가 들어오면 원문 UNIQUE는
    -- 인덱스 삽입 자체가 실패한다. 재료는 종류·금액·문구 셋이다.
    donation_sha256    VARCHAR(64) NOT NULL,

    -- 🔴 저장 재시도가 중복을 만들지 않게 하는 마지막 방어선. 채팅과 같은 모양이고
    -- **시각이 열쇠에 들어 있다** — 그래서 접히는 것은 「같은 사람이 같은 밀리초에
    -- 같은 내용으로」뿐이다. 같은 사람이 같은 금액을 연달아 후원하는 것은 정상이고
    -- 그 둘은 received_at이 달라 안 접힌다.
    --
    -- 없으면 무슨 일이 나나: 배치 저장이 중간에 끊기면(연결 절단) 커밋된 앞 행이
    -- 되돌려져 다시 들어가 같은 후원이 두 번 보인다. 채팅은 이 제약이 흡수하는데
    -- 후원에는 흡수 장치가 없었다(POK-234 로컬 리뷰 라운드 1).
    CONSTRAINT uq_chat_donations_fingerprint
        UNIQUE (stream_id, donator_channel_id, received_at, donation_sha256)
);

COMMENT ON TABLE chat_donations IS '치지직 후원 이벤트. 치지직이 시각을 안 주므로 received_at(우리 시각)만 있다. 지문은 저장 재시도의 중복을 막는다';
COMMENT ON COLUMN chat_donations.donation_sha256 IS 'sha256(종류|금액|문구). 재시도 중복만 막고 진짜 연속 후원은 received_at이 갈라 준다';
COMMENT ON COLUMN chat_donations.received_at IS '우리가 받은 시각. chat_messages.message_time(치지직 시계)과 축이 다르다';
COMMENT ON COLUMN chat_donations.pay_amount IS '원. 치지직이 문자열로 주며 숫자로 못 읽으면 NULL';

-- 범위 창구(POK-234)가 타는 색인. 후원의 시각 축은 received_at 하나뿐이다.
CREATE INDEX idx_chat_donations_stream_received ON chat_donations (stream_id, received_at);
