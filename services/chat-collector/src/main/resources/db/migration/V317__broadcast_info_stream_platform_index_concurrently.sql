-- 🔴 트랜잭션 밖에서 돈다. CREATE INDEX CONCURRENTLY 는 트랜잭션 안에서 못 쓴다. Flyway는 아래 주석 줄을 읽지 않는다
-- (설정은 .conf로만, README 마이그레이션 절). 문장이 CONCURRENTLY 하나뿐이라 Flyway가 스스로 트랜잭션 밖에서 돌린다(V314와 같다).
-- 다른 종류 문장을 섞으면 V315처럼 .conf가 필요하다.
-- executeInTransaction=false

-- 방송 정보를 플랫폼마다 최신으로 고르는 색인(POK-397). 한 방송을 치지직·SOOP에 같이 내보내면 줄이 번갈아 쌓이는데,
-- (stream_id, observed_at)만으로는 SOOP을 중간에 끈 방송·SOOP 없는 방송에서 SOOP 최신을 찾느라 그 방송 이력을 처음까지 훑는다
-- (계획 검증 실측: 2만 줄 방송에서 버린 줄 19,900, 9.6ms → 이 색인으로 0.06ms).
-- 앞 칸이 stream_id라 「시각만」 색인이 아니고(V312 주석), 단건 최신·추이·제목 조회는 그대로 idx_stream_observed를 탄다(같은 실측).
--
-- CONCURRENTLY·IF NOT EXISTS인 이유와 INVALID 색인 확인법은 V309 머리말과 같다. 배포 뒤 한 번 본다:
--   SELECT indexrelid::regclass, indisvalid FROM pg_index
--    WHERE indexrelid::regclass::text = 'idx_broadcast_info_stream_platform_observed';
-- indisvalid 가 false 면 DROP INDEX CONCURRENTLY 로 지우고 이 마이그레이션을 다시 돌린다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_broadcast_info_stream_platform_observed
    ON broadcast_info (stream_id, platform, observed_at);
