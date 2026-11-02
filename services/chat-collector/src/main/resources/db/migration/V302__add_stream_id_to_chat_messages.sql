-- V301 주석이 예고한 칸이다("stream_id는 아직 없다 — 방송 경계는 계약9 이후에야 알 수 있어
-- 그때 별도 마이그레이션으로 추가한다").
--
-- NULL을 허용하는 이유가 둘이다. ① 이미 쌓인 옛 채팅은 채울 값이 없다.
-- ② 방송 번호를 모르는 채로 채팅이 와도 수집이 멈추면 안 된다(편지 없이 붙는
-- 옛 경로 CHZZK_ENABLED가 그렇다). NULL이 곧 "모른다"는 표시이고, 읽는 쪽은
-- 그것으로 구분한다.
--
-- 폭은 clip의 broadcasts.stream_id와 같은 VARCHAR(128)이다. 같은 값이 두 표에 사는데
-- 폭이 다르면 한쪽에서 잘린다. 좁으면 22001(string data right truncation)로 거절당하고
-- 그것은 SQLSTATE 22류라 ChatPersister가 격리 폐기한다 — 채팅이 조용히 사라진다.
--
-- 지문 제약(uq_chat_messages_fingerprint)에는 넣지 않는다. 넣으면 방송 경계에서
-- 같은 채팅이 두 번 들어간다 — 방송을 껐다 켜면 번호만 갈아끼우고 소켓은 그대로라
-- 재연결·이중 처리로 겹치는 프레임이 서로 다른 번호를 달고 온다.
ALTER TABLE chat_messages ADD COLUMN stream_id VARCHAR(128);

-- 부분 인덱스다. 옛 채팅은 전부 NULL이고 판별기는 번호 있는 것만 뽑는다
-- (auth의 V104·V107·V108이 같은 기법을 쓴다).
CREATE INDEX idx_chat_messages_stream_received
    ON chat_messages (stream_id, received_at)
    WHERE stream_id IS NOT NULL;
