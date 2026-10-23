-- 🔴 트랜잭션 밖에서 돈다. CREATE INDEX CONCURRENTLY 는 트랜잭션 안에서 못 쓴다. Flyway는 아래 주석 줄을 읽지 않는다
-- (설정은 .conf로만, README 마이그레이션 절). 문장이 전부 CONCURRENTLY라 Flyway가 스스로 트랜잭션 밖에서 돌린다.
-- 다른 종류 문장을 섞으면 V315처럼 .conf가 필요하다.
-- executeInTransaction=false

-- 플랫폼을 앞에 둔 색인 넷(POK-397). 채널로 찾는 자리(탈퇴 정리)는 전부 (플랫폼, 채널)로 찾고, 채팅 지문은 플랫폼마다 따로 센다.
-- 옛 색인을 바꾸는 일(지문 제약 교체·옛 채널 색인 삭제)은 V315가 한다. 여기서는 만들기만 해서 실패해도 옛 모양이 그대로 산다.
--
-- CONCURRENTLY·IF NOT EXISTS인 이유와 INVALID 색인 확인법은 V309 머리말과 같다. 배포 뒤 한 번 본다:
--   SELECT indexrelid::regclass, indisvalid FROM pg_index
--    WHERE indexrelid::regclass::text IN ('uq_chat_messages_fingerprint', 'idx_chat_messages_platform_channel_received',
--          'idx_chat_donations_platform_channel_received', 'idx_broadcast_info_platform_channel_observed');
-- (첫 이름은 V315가 uq_chat_messages_platform_fingerprint를 제약으로 올리며 바꾼 이름이다.)
-- indisvalid 가 false 면 DROP INDEX CONCURRENTLY 로 지우고 이 마이그레이션을 다시 돌린다.
--
-- 🔴 「시각만」 색인을 두지 않는다(V312 주석). 넷 다 앞 칸이 platform이라 방송 번호로 찾는 기존 조회가 이것을 안 고른다.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_chat_messages_platform_fingerprint
    ON chat_messages (platform, channel_id, sender_channel_id, message_time, content_sha256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_messages_platform_channel_received
    ON chat_messages (platform, channel_id, received_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_donations_platform_channel_received
    ON chat_donations (platform, channel_id, received_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_broadcast_info_platform_channel_observed
    ON broadcast_info (platform, channel_id, observed_at);
