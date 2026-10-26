-- 30 권한 — media 두 계정과 앱 사용자 둘에 stream_* 표 단위 권한을 준다(POK-311 · POK-407 목록 ·
-- ALTER DEFAULT PRIVILEGES 는 쓰지 않는다).
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 30_grants.sql (마스터 사용자). 다시 돌려도 같다.
--
-- 마스터는 media_owner 의 권한을 상속하지 않으므로(10 의 INHERIT FALSE) 표 소유자로 바꿔서 준다.
-- 감싸는 두 줄을 빼면 42501 이다(H2).
--
-- media_indexer 의 DELETE(조각 · 세션 · GAP · outbox) · stream_cutoffs UPDATE · DELETE 는 M6 만료 · outbox 몫이다
-- (ADR-0006 개정 — docs/decisions/2026-10-26-media-indexer-m6-delete-grants.md). TRUNCATE · DDL 은 없고, seq 하한
-- 표는 SELECT 만이다(하한은 stream_segments 트리거가 정의자 권한으로 올린다).
--
-- 목록 정본은 media 쪽이다. media_indexer = media/internal/pgtest/role.go 의 mediaIndexerGrants,
-- media_janitor = media/internal/index/local_cleanup_role_pg_test.go 의 lcRoleGrants. 이 파일의 GRANT 가
-- 두 목록과 같은 집합인지는 media 의 prod_sql_contract_test.go 가 강제한다.
--
-- 앱 사용자의 교차 권한은 3번 목록(services/README.md 「서버별 DB 계정과 권한」)이 정본이다 — app_clip 은 탈퇴
-- 정리 · 조각 조회(stream_segments 읽기 · 지우기, stream_sessions 읽기), app_chat_collector 는 채팅 시각 → 영상
-- 위치(stream_segments 읽기). app_chat_detector 의 chat_messages SELECT 는 표 주인인 수집 서버 Flyway(V316)가
-- 주므로 여기 없다.
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

SET ROLE media_owner;

GRANT SELECT, INSERT, UPDATE, DELETE ON stream_segments TO media_indexer;
GRANT SELECT, INSERT, UPDATE, DELETE ON stream_sessions TO media_indexer;
GRANT SELECT, INSERT, UPDATE, DELETE ON stream_cutoffs TO media_indexer;
GRANT SELECT, INSERT, UPDATE, DELETE ON stream_published_gaps TO media_indexer;
GRANT SELECT ON stream_seq_floors TO media_indexer;
GRANT SELECT, INSERT, UPDATE, DELETE ON broadcast_lifecycle_outbox TO media_indexer;

GRANT SELECT ON stream_segments, stream_sessions, stream_cutoffs TO media_janitor;
GRANT SELECT ON stream_published_gaps TO media_janitor;
GRANT UPDATE (local_path) ON stream_segments TO media_janitor;

GRANT SELECT, DELETE ON stream_segments TO app_clip;
GRANT SELECT ON stream_sessions TO app_clip;
GRANT SELECT ON stream_segments TO app_chat_collector;

RESET ROLE;
