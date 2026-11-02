-- 🔴 트랜잭션 밖에서 돈다. CREATE INDEX CONCURRENTLY 는 트랜잭션 안에서 못 쓴다.
-- executeInTransaction=false

-- 탈퇴 정리·60일 보관(POK-256)이 타는 색인. 채팅 표는 이미 둘이 있다:
-- (channel_id, received_at)는 V301, (received_at) WHERE stream_id IS NOT NULL은 V305.
-- 방송 번호가 없던 옛 줄(V302 전)만 시각으로 찾을 색인이 없어 아래 부분 색인 하나를 더한다(그 줄은 안 늘어난다).
-- 후원·방송 정보는 채널로 지울 색인만 더한다.
--
-- 🔴 「시각만」 색인(received_at · observed_at)을 두지 마라. 처음에 뒀더니 기존 조회 둘(후원 창구, 방송 제목)이
-- 방송 번호 색인 대신 그것을 골라 타기 시작했다(ChatWindowQueryTest · BroadcastTitlePlanTest가 잡았다).
-- 60일 정리는 그 둘에서 번호(id) 순 앞쪽만 본다(ChatPurgeStore.deleteExpiredBatch).
--
-- CONCURRENTLY·IF NOT EXISTS인 이유와 INVALID 색인 확인법은 V309 머리말과 같다. 배포 뒤 한 번 본다:
--   SELECT indexrelid::regclass, indisvalid FROM pg_index WHERE NOT indisvalid;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_messages_received_no_stream
    ON chat_messages (received_at) WHERE stream_id IS NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_donations_channel_received
    ON chat_donations (channel_id, received_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_broadcast_info_channel_observed
    ON broadcast_info (channel_id, observed_at);
