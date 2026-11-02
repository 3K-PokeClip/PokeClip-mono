-- 50 advisory 회수 확인 — 마스터가 pg_advisory_xact_lock(bigint) 의 EXECUTE 를 PUBLIC 에서 회수할 수
-- 있는지만 본다. 끝에서 ROLLBACK 하므로 아무것도 바꾸지 않는다(K7 (가) — 회수하지 않는다, kty 2026-10-24).
--
-- 실행: psql -v ON_ERROR_STOP=1 -f 50_advisory_probe.sql (마스터 사용자 · -1 없이 · 다른 단계와 떼어 한 번).
-- 출력은 운영 관측 기록이다(볼트 raw · POK-428 코멘트) — K7 (가)라 결과와 무관하게 회수하지 않는다. 판정(셋
-- 모두 정상 관측 — 뒤이어 40 을 다시 돌려 VERIFY OK 로 무변경을 확인한다):
--   after 행의 public_execute = f            회수 가능
--   WARNING no privileges could be revoked   회수 불가(after = t)
--   ERROR 42501                              회수 불가(트랜잭션째 끝남)
--
-- 회수하지 않는 까닭은 쓰는 계정이 다섯이어서다(media_janitor 만 쓰지 않는다): 사이드카 media_indexer 의 컷오프
-- 잠금(media/internal/index/store.go 의 lockCutoffStreamSQL) · app_auth 의 맨 이름 pg_advisory_xact_lock(hashtext(?))
-- · app_clip 의 (integer, integer) 판 · 3번 서버 넷(앱 사용자 넷)의 Flyway 직렬화. 다시 주어도 그 넷과
-- media_indexer 가 bigint 열쇠를 잡을 수 있어 컷오프 열쇠를 막는 위협은 그대로이고, Flyway 판이 잠금 함수를
-- 바꾸면 운영 부팅이 42501 로 멈춘다.
--
-- 내장 함수와 대상 함수는 pg_catalog 로 한정한다 — public CREATE 를 다시 연 DB 에서도 누군가 public 에 심은
-- (text, text, text) 판이 마스터 신분으로 돌지 않게(search_path 가로채기 · 심층 방어, media/README.md).
\set ON_ERROR_STOP on

BEGIN;

SELECT 'before' AS phase,
       pg_catalog.has_function_privilege('public', 'pg_catalog.pg_advisory_xact_lock(bigint)',
                                         'EXECUTE') AS public_execute,
       pg_catalog.has_function_privilege('media_indexer', 'pg_catalog.pg_advisory_xact_lock(bigint)',
                                         'EXECUTE') AS media_indexer_execute;

REVOKE EXECUTE ON FUNCTION pg_catalog.pg_advisory_xact_lock(bigint) FROM PUBLIC;

SELECT 'after' AS phase,
       pg_catalog.has_function_privilege('public', 'pg_catalog.pg_advisory_xact_lock(bigint)',
                                         'EXECUTE') AS public_execute,
       pg_catalog.has_function_privilege('media_indexer', 'pg_catalog.pg_advisory_xact_lock(bigint)',
                                         'EXECUTE') AS media_indexer_execute;

ROLLBACK;
