-- 50 advisory 회수 확인 — 마스터가 pg_advisory_xact_lock(bigint) 의 EXECUTE 를 PUBLIC 에서 회수할 수
-- 있는지만 본다. 끝에서 ROLLBACK 하므로 아무것도 바꾸지 않는다(K7 — 실제 회수는 1단-나).
--
-- 실행: psql -v ON_ERROR_STOP=1 -f 50_advisory_probe.sql (마스터 사용자 · -1 없이 · 다른 단계와 떼어 한 번).
-- 출력은 1단-나의 재료다(볼트 raw · POK-304 코멘트). 판정(셋 모두 정상 관측 — 뒤이어 40 을 다시 돌려
-- VERIFY OK 로 무변경을 확인한다):
--   after 행의 public_execute = f            회수 가능
--   WARNING no privileges could be revoked   회수 불가(after = t)
--   ERROR 42501                              회수 불가(트랜잭션째 끝남)
--
-- 쓰는 쪽이 둘이라 지금 회수하지 않는다: 사이드카 media_indexer 의 컷오프 잠금
-- (media/internal/index/store.go 의 lockCutoffStreamSQL)과 3번 서버 넷의 Flyway 직렬화다.
\set ON_ERROR_STOP on

BEGIN;

SELECT 'before' AS phase,
       has_function_privilege('public', 'pg_advisory_xact_lock(bigint)', 'EXECUTE') AS public_execute,
       has_function_privilege('media_indexer', 'pg_advisory_xact_lock(bigint)', 'EXECUTE') AS media_indexer_execute;

REVOKE EXECUTE ON FUNCTION pg_advisory_xact_lock(bigint) FROM PUBLIC;

SELECT 'after' AS phase,
       has_function_privilege('public', 'pg_advisory_xact_lock(bigint)', 'EXECUTE') AS public_execute,
       has_function_privilege('media_indexer', 'pg_advisory_xact_lock(bigint)', 'EXECUTE') AS media_indexer_execute;

ROLLBACK;
