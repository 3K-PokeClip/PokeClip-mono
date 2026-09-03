-- 닉네임은 바뀌는 값이라 지문 UNIQUE(uq_chat_messages_fingerprint)에 넣지 않는다.
-- 과거 행은 NULL로 남는다(소급 없음 — PRD 비목표).
ALTER TABLE chat_messages
    ADD COLUMN nickname  TEXT,
    ADD COLUMN user_role VARCHAR(32);

COMMENT ON COLUMN chat_messages.nickname  IS '치지직 profile.nickname. 수신 시점 값. 옛 행은 NULL';
COMMENT ON COLUMN chat_messages.user_role IS '치지직 userRoleCode(streamer·common_user·streaming_channel_manager·streaming_chat_manager). 옛 행은 NULL';

-- 시각 범위 창구(POK-234)가 타는 색인. 보정값 기준이 치지직 시계(message_time)라 received_at이 아니다.
-- 조건이 stream_id IS NOT NULL이라 마이그레이션 시점에 쓸 엔트리가 0개다(옛 행은 전부 NULL) —
-- 100만 행에서 0.03초로 실측된 자리다(POK-127 반박 근거).
CREATE INDEX idx_chat_messages_stream_message_time
    ON chat_messages (stream_id, message_time) WHERE stream_id IS NOT NULL;
