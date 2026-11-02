-- 🔴 트랜잭션 밖에서 돈다. DROP INDEX CONCURRENTLY 는 트랜잭션 안에서 못 쓴다.
-- executeInTransaction=false
-- 🔴 위 줄은 표시일 뿐이고 Flyway가 읽지 않는다. 실제 스위치는 옆의 V315__chat_platform_swap_and_validate.sql.conf 다
-- (Flyway 12.4 OSS는 스크립트 설정을 그 파일에서만 읽는다). V309·V312는 문장이 전부 CONCURRENTLY라 Flyway가 스스로 트랜잭션
-- 밖으로 돌렸지만, 이 파일은 DO 블록·VALIDATE(트랜잭션 가능)와 DROP INDEX CONCURRENTLY(불가)가 섞여 .conf 없이는
-- 「Detected both transactional and non-transactional statements」로 부팅이 실패한다(실측).

-- V314가 만든 색인으로 옛 모양을 바꾼다(POK-397). 트랜잭션 밖이라 중간에 끊기면 앞부분만 적용된 채 남는다. 그래서 문장마다
-- 다시 돌려도 같은 결과가 되게 썼다. 끊겼으면 flyway repair로 실패 줄을 지우고 다시 부팅한다.

-- ① 지문 제약을 플랫폼부터 보는 것으로 바꾼다. 이름은 그대로 둔다: 배포 중 옛 인스턴스의 ON CONFLICT ON CONSTRAINT
-- uq_chat_messages_fingerprint 가 계속 돈다. USING INDEX 가 색인 이름을 제약 이름으로 바꿔 준다(계획 검증 실측).
-- DROP과 ADD가 한 ALTER라 제약이 없는 순간이 없다. 강한 잠금은 이 한 문장 동안만이다(100만 줄 1.2ms, 표를 안 읽는다).
--
-- 이미 바뀌었으면(정의에 platform이 있으면) 건너뛴다. 🔴 바꿀 것이 남았는데 새 색인이 없거나 INVALID면 실패시킨다.
-- 조용히 건너뛰면 옛 제약이 남은 채 이 마이그레이션이 성공으로 적히고, SOOP 채팅이 같은 아이디 치지직 채팅과 지문이 같을 때
-- 버려진다(계획 검증 F6). 그때는 V314 머리말대로 색인을 다시 만든다.
-- to_regclass·::regclass는 search_path로 찾는다(공유 DB에서 남의 스키마 이름과 섞이지 않는다).
DO $$
BEGIN
    -- 지문 교체는 V313과 같은 강한 잠금이라 같은 이유로 대기를 5초로 자른다(앞 읽기를 기다리는 동안 채팅 INSERT가 줄을 선다).
    -- DO는 자동 커밋 문장 하나가 한 트랜잭션이라 LOCAL이 이 블록에만 산다. 넘기면 아무것도 안 바꾸고 실패하며 이력에 실패 줄이 남는다. 다음 부팅도 그 줄 때문에 막히므로 `flyway repair` 뒤 다시 배포한다(감사 2회차 실측).
    SET LOCAL lock_timeout = '5s';
    IF pg_get_constraintdef((SELECT oid FROM pg_constraint
                              WHERE conrelid = 'chat_messages'::regclass
                                AND conname = 'uq_chat_messages_fingerprint')) LIKE '%platform%' THEN
        RETURN;
    END IF;
    IF to_regclass('uq_chat_messages_platform_fingerprint') IS NULL
       OR NOT (SELECT indisvalid FROM pg_index
                WHERE indexrelid = to_regclass('uq_chat_messages_platform_fingerprint')) THEN
        RAISE EXCEPTION 'uq_chat_messages_platform_fingerprint 가 없거나 INVALID 다. V314 머리말대로 다시 만든 뒤 돌린다';
    END IF;
    ALTER TABLE chat_messages
        DROP CONSTRAINT uq_chat_messages_fingerprint,
        ADD CONSTRAINT uq_chat_messages_fingerprint UNIQUE USING INDEX uq_chat_messages_platform_fingerprint;
END $$;

-- ② 옛 채널 색인 셋. 새 색인과 앞 칸 순서만 다르고 같은 일을 해 쓰기 비용만 늘린다. 판별 서버는 채널로 안 찾는다
-- (ChatWindowReader, 계획 검증 확인). 🔴 배포 중 옛 인스턴스의 탈퇴 정리는 플랫폼 없이 채널로 찾아 이 뒤로 Seq Scan이 된다.
-- 정리는 탈퇴 때만 돌아 겹치는 동안만 참는다.
DROP INDEX CONCURRENTLY IF EXISTS idx_chat_messages_channel_received;
DROP INDEX CONCURRENTLY IF EXISTS idx_chat_donations_channel_received;
DROP INDEX CONCURRENTLY IF EXISTS idx_broadcast_info_channel_observed;

-- ③ V313의 CHECK를 검증한다. 표 전체를 읽지만 쓰기를 막지 않는 잠금(ShareUpdateExclusive)이다(100만 줄 482ms 실측).
-- 이미 검증된 제약이면 바로 끝난다.
ALTER TABLE chat_messages   VALIDATE CONSTRAINT ck_chat_messages_platform;
ALTER TABLE chat_donations  VALIDATE CONSTRAINT ck_chat_donations_platform;
ALTER TABLE broadcast_info  VALIDATE CONSTRAINT ck_broadcast_info_platform;
ALTER TABLE purged_channels VALIDATE CONSTRAINT ck_purged_channels_platform;
