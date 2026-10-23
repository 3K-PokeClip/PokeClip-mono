-- 10 역할 — media 의 DDL 소유 역할과 두 로그인 계정을 만들고 public 스키마 권한을 정한다.
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 10_roles.sql (마스터 사용자). 다시 돌려도 같은 상태로 수렴한다.
--
--   media_owner   NOLOGIN. stream_* 표 · 함수의 소유자(20 이 이 역할로 만든다).
--   media_indexer 사이드카 계정(IAM 인증). 권한 정본 = media/internal/pgtest/role.go.
--   media_janitor 로컬 ② 정리기 계정(IAM 인증). 권한 정본 = media/README.md media_janitor 절.
--
-- 마스터는 media_owner 의 권한을 상속하지 않는다(INHERIT FALSE). 대신 SET ROLE 로 바꿀 수만 있다
-- (SET TRUE) — 20 · 30 이 그렇게 쓴다. PG16+ 에서 CREATEROLE 생성자는 만든 역할의 ADMIN 만 받고
-- SET 은 받지 않으므로 이 자기 부여가 없으면 SET ROLE 이 42501 이다.
--
-- 연결 상한 20 · statement_timeout 60s 는 측정값이 아니다(O7e 에서 다시 본다).
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_owner') THEN
        CREATE ROLE media_owner NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_indexer') THEN
        CREATE ROLE media_indexer LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_janitor') THEN
        CREATE ROLE media_janitor LOGIN;
    END IF;
END $do$;

ALTER ROLE media_owner NOLOGIN;
ALTER ROLE media_indexer LOGIN CONNECTION LIMIT 20;
ALTER ROLE media_indexer SET statement_timeout = '60s';
ALTER ROLE media_janitor LOGIN;

GRANT rds_iam TO media_indexer, media_janitor;
GRANT media_owner TO CURRENT_USER WITH SET TRUE, INHERIT FALSE;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO media_owner;
