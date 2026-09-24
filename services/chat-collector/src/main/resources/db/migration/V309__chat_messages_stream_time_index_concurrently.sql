-- 🔴 트랜잭션 밖에서 돈다. CREATE INDEX CONCURRENTLY 는 트랜잭션 안에서 못 쓴다.
-- 이 한 줄이 빠지면 Flyway 가 기본대로 감싸고 마이그레이션이 통째로 실패한다.
-- executeInTransaction=false

-- 시각 범위 창구(POK-234)가 타는 색인. 보정값 기준이 치지직 시계(message_time)라
-- received_at 이 아니다.
--
-- 🔴 <b>CONCURRENTLY 인 이유는 운영에서 쓰기를 막지 않기 위해서다</b>(봇 codex P1).
-- 처음엔 V306 안에서 보통 CREATE INDEX 로 만들었고 근거는 「조건이 stream_id IS NOT NULL
-- 이라 쓸 엔트리가 0개」였다. <b>그 근거가 낡았다</b> — V302(stream_id 칸)가 이미 배포돼
-- 그 뒤 수집이 채운 행이 쌓여 있다. 보통 CREATE INDEX 는 그 표에 잠금을 걸어 INSERT 를
-- 막고, 빌드가 길어지면 적재가 멈춰 바구니가 차고 <b>라이브 채팅이 버려진다.</b>
-- 채팅은 되돌릴 수 없다.
--
-- 🔴 <b>IF NOT EXISTS 가 필요하다 — 트랜잭션 밖이라는 것의 진짜 대가다.</b>
-- Flyway 는 이력 테이블 락으로 같은 마이그레이션의 동시 실행을 막는데, CONCURRENTLY 는
-- <b>그 락 밖에서 돈다.</b> 그래서 프로세스·컨텍스트가 둘 이상 동시에 뜨면 둘 다
-- 「V309 는 아직 안 돌았다」를 보고 둘 다 만들려 하고, 진 쪽이
-- relation "..." already exists 로 죽는다 — <b>부팅 실패다.</b>
-- 처음에 IF NOT EXISTS 를 일부러 뺐다가 실측으로 밟았다(컨텍스트 둘을 띄우는 검사).
-- 운영도 같다: 롤링 배포에서 인스턴스 둘이 겹치는 순간이 정확히 그 자리다.
--
-- <b>대가는 INVALID 인덱스를 통과시키는 것이다.</b> 앞선 CONCURRENTLY 가 중간에 실패하면
-- 쓰이지 않는 INVALID 인덱스가 남는데, IF NOT EXISTS 는 그것을 「있다」로 보고 넘어간다 —
-- 색인이 없는 것과 같은 상태로 배포가 초록불이 된다. <b>배포 뒤 한 번 확인한다:</b>
--   SELECT indexrelid::regclass, indisvalid FROM pg_index
--    WHERE indexrelid = 'idx_chat_messages_stream_message_time'::regclass;
-- indisvalid 가 false 면 DROP INDEX CONCURRENTLY 로 지우고 이 마이그레이션을 다시 돌린다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_messages_stream_message_time
    ON chat_messages (stream_id, message_time) WHERE stream_id IS NOT NULL;
