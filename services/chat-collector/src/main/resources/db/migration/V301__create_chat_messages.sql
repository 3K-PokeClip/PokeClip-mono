-- 수신한 치지직 채팅 원본. chat 계열(collector가 쓰고 detector가 읽는다)의
-- 공동 소유다 — 같은 담당·같은 V3xx 대역이라 "서로의 표를 직접 읽지 않는다"
-- 규칙의 예외가 아니라 한 소유자의 두 프로세스다.
-- stream_id(방송 한 회)는 아직 없다 — 방송 경계는 POK-82(계약9) 이후에야
-- 알 수 있어, 그때 별도 마이그레이션으로 추가한다. 지금 방송 식별자는 채널이다.
CREATE TABLE chat_messages (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id        TEXT        NOT NULL,
    sender_channel_id TEXT        NOT NULL,
    content           TEXT        NOT NULL,
    -- 치지직이 찍은 시각(messageTime, epoch ms). eventSentAt은 오프셋 없는
    -- KST라 쓰지 않는다 — UTC로 파싱하면 9시간 어긋나고 오류도 안 난다.
    message_time      TIMESTAMPTZ NOT NULL,
    -- 우리가 프레임을 받은 시각(UTC). 전달 지연(실측 중앙값 175ms)만큼
    -- message_time보다 늦다.
    received_at       TIMESTAMPTZ NOT NULL,
    -- 본문 지문. 원문 대신 해시를 UNIQUE에 넣는 이유는 btree 인덱스 행 크기
    -- 제한(~2704B) 때문 — 긴 도배 본문이면 인덱스 생성이 실패한다.
    -- CHAR가 아니라 VARCHAR인 이유: PostgreSQL CHAR는 공백 패딩 때문에
    -- 인덱스를 못 탄다 (auth에서 배운 것).
    content_sha256    VARCHAR(64) NOT NULL,
    -- 치지직은 메시지 고유 ID를 안 준다(2026-08-14 공식 문서 재확인).
    -- 재연결 직후 우리 쪽 이중 처리가 같은 채팅을 두 번 넣으려 할 때
    -- 이 제약이 마지막 방어선이다. 대가: 같은 사람이 같은 ms에 같은 본문을
    -- 보내는 정상 메시지(연타 도배)도 접힐 수 있다 — 알고 받아들인 결정이다.
    -- ChatPersister가 접힌 수를 conflicts 카운터로 세서 요약 로그에 드러낸다.
    CONSTRAINT uq_chat_messages_fingerprint
        UNIQUE (channel_id, sender_channel_id, message_time, content_sha256)
);

-- 판별기의 시간창 쿼리용 (channel + 시각 범위 스캔)
CREATE INDEX idx_chat_messages_channel_received
    ON chat_messages (channel_id, received_at);
