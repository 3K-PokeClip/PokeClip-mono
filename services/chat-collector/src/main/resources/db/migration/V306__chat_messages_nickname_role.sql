-- 닉네임은 바뀌는 값이라 지문 UNIQUE(uq_chat_messages_fingerprint)에 넣지 않는다.
-- 과거 행은 NULL로 남는다(소급 없음 — PRD 비목표).
ALTER TABLE chat_messages
    ADD COLUMN nickname  TEXT,
    ADD COLUMN user_role VARCHAR(32);

COMMENT ON COLUMN chat_messages.nickname  IS '치지직 profile.nickname. 수신 시점 값. 옛 행은 NULL';
COMMENT ON COLUMN chat_messages.user_role IS '치지직 userRoleCode(streamer·common_user·streaming_channel_manager·streaming_chat_manager). 옛 행은 NULL';

-- 🔴 시각 범위 창구가 타는 색인은 여기 없다 — V309 로 옮겼다(봇 codex P1).
-- 이 자리에 있던 CREATE INDEX 는 조건이 stream_id IS NOT NULL 이라 「마이그레이션 시점에
-- 쓸 엔트리가 0개」라고 적혀 있었는데, 그 근거는 V302(stream_id 칸)를 <b>막 넣을 때</b>의
-- 이야기다. V302 는 이미 배포됐고 그 뒤로 수집이 돌았으므로 운영 표에는 stream_id 가
-- 채워진 행이 쌓여 있다. 보통 CREATE INDEX 는 그 표의 쓰기를 막으므로, 빌드가 길어지면
-- 적재가 멈추고 바구니가 차서 <b>라이브 채팅이 버려진다.</b>
