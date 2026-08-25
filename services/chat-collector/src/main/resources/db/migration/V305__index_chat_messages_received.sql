-- 판별 서버(POK-120)가 「최근에 채팅이 온 방송」을 매초 뽑는다. 그 조회는 received_at만 걸어서
-- V302의 idx_chat_messages_stream_received(선두 칸이 stream_id)를 못 탄다 — 60만 행에서
-- Parallel Seq Scan 19ms를 실측했다(POK-120 계획 검증 F7). 표는 계속 쌓이고 DB는 공유라
-- 이웃 서비스 조회에도 부담이 간다.
--
-- 부분 인덱스인 이유는 V302와 같다 — 판별기는 방송 번호가 있는 것만 본다.
--
-- 🔴 CONCURRENTLY가 아니다. 이 문장은 chat_messages에 ShareLock을 잡고, 그동안 수집 서버의
-- INSERT가 막힌다(직접 실측: 60만 행에서 ShareLock · 63.099ms). V302도 같은 모양이라 새
-- 관행은 아니고, CONCURRENTLY는 트랜잭션 밖에서만 돌아 Flyway 특별 처리가 필요해서 안 쓴다.
-- 실운영 규모에서는 초 단위가 될 수 있으므로 배포 시점을 방송이 적은 때로 고르는 편이 낫다.
CREATE INDEX idx_chat_messages_received
    ON chat_messages (received_at)
    WHERE stream_id IS NOT NULL;
