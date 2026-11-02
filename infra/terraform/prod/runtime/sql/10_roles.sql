-- 10 역할 — media 의 DDL 소유 역할 · 두 로그인 계정과 3번 서버 넷의 앱 사용자를 만들고 public 스키마 권한 · 앱
-- 스키마 · DB 권한(CONNECT · TEMP)을 정한다.
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 10_roles.sql (마스터 사용자). 다시 돌려도 같은 상태로 수렴한다.
--
--   media_owner   NOLOGIN. stream_* 표 · 함수의 소유자(20 이 이 역할로 만든다).
--   media_indexer 사이드카 계정(IAM 인증). 권한 정본 = media/internal/pgtest/role.go.
--   media_janitor 로컬 ② 정리기 계정(IAM 인증). 권한 정본 = media/README.md media_janitor 절.
--   app_auth · app_clip · app_chat_collector · app_chat_detector
--                 3번 서버 넷의 계정(IAM 인증 · 비밀번호 없음 — POK-407). 권한 정본 = services/README.md
--                 「서버별 DB 계정과 권한」. 서버마다 Flyway 로 자기 표를 자기 계정으로 만든다 — 자리는 public 이
--                 아니라 계정과 이름이 같은 자기 스키마다(K8 · 1단-나). 스키마는 마스터(접속 신분)가 소유하고
--                 앱에는 USAGE · CREATE 만 준다(K9 — 앱이 자기 스키마 권한을 남에게 주지 못한다). 판별 서버는
--                 수집 서버 스키마의 USAGE 를 받아 chat_messages 를 한정 이름으로 읽는다. 서버가 처음 뜨기 전에
--                 있어야 한다 — 수집 서버의 Flyway(V316)는 app_chat_detector 가 있을 때만 chat_messages SELECT 를
--                 준다. stream_* 교차 권한은 30 이 준다.
--
-- DB 권한: 이 DB 의 CONNECT · TEMP 를 PUBLIC 에서 거두고 CONNECT 만 계정 여섯에 하나씩 준다. 그래서 새 계정은
-- CONNECT 를 따로 받아야 접속한다. psql 변수를 쓰지 않으므로 DB 이름은 동적 SQL(current_database)로 넣는다 —
-- 이 줄들은 prod_sql_contract_test.go 의 GRANT 대조 밖이고 40 이 잰다. 다른 DB(postgres · template1)는 건드리지
-- 않는다(K12).
--
-- 앱 사용자 이름의 원본은 아래 DO 블록의 CREATE ROLE 줄이다. prod/runtime 의 출력 app_db_users 가 그 줄을 읽고,
-- media 의 prod_sql_contract_test.go 가 3번 목록과 대조한다 — 한 줄에 한 사용자 꼴을 바꾸지 않는다.
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
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_auth') THEN
        CREATE ROLE app_auth LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_clip') THEN
        CREATE ROLE app_clip LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_chat_collector') THEN
        CREATE ROLE app_chat_collector LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_chat_detector') THEN
        CREATE ROLE app_chat_detector LOGIN;
    END IF;
END $do$;

ALTER ROLE media_owner NOLOGIN;
ALTER ROLE media_indexer LOGIN CONNECTION LIMIT 20;
ALTER ROLE media_indexer SET statement_timeout = '60s';
ALTER ROLE media_janitor LOGIN;
ALTER ROLE app_auth LOGIN;
ALTER ROLE app_clip LOGIN;
ALTER ROLE app_chat_collector LOGIN;
ALTER ROLE app_chat_detector LOGIN;

GRANT rds_iam TO media_indexer, media_janitor;
GRANT media_owner TO CURRENT_USER WITH SET TRUE, INHERIT FALSE;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO media_owner;

-- 앱 사용자 — 문장 하나가 3번 목록 한 줄이다(사용자를 한 문장에 묶지 않는다). public CREATE 회수는 1단-가 판이
-- 이미 적용된 DB 에서도 같은 상태로 수렴하게 둔다.
REVOKE CREATE ON SCHEMA public FROM app_auth;
REVOKE CREATE ON SCHEMA public FROM app_clip;
REVOKE CREATE ON SCHEMA public FROM app_chat_collector;
REVOKE CREATE ON SCHEMA public FROM app_chat_detector;

CREATE SCHEMA IF NOT EXISTS app_auth;
CREATE SCHEMA IF NOT EXISTS app_clip;
CREATE SCHEMA IF NOT EXISTS app_chat_collector;
CREATE SCHEMA IF NOT EXISTS app_chat_detector;

GRANT rds_iam TO app_auth;
GRANT USAGE, CREATE ON SCHEMA app_auth TO app_auth;
GRANT rds_iam TO app_clip;
GRANT USAGE, CREATE ON SCHEMA app_clip TO app_clip;
GRANT rds_iam TO app_chat_collector;
GRANT USAGE, CREATE ON SCHEMA app_chat_collector TO app_chat_collector;
GRANT rds_iam TO app_chat_detector;
GRANT USAGE, CREATE ON SCHEMA app_chat_detector TO app_chat_detector;
GRANT USAGE ON SCHEMA app_chat_collector TO app_chat_detector;

DO $do$
BEGIN
    EXECUTE pg_catalog.format('REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', pg_catalog.current_database());
    EXECUTE pg_catalog.format('GRANT CONNECT ON DATABASE %I TO media_indexer', pg_catalog.current_database());
    EXECUTE pg_catalog.format('GRANT CONNECT ON DATABASE %I TO media_janitor', pg_catalog.current_database());
    EXECUTE pg_catalog.format('GRANT CONNECT ON DATABASE %I TO app_auth', pg_catalog.current_database());
    EXECUTE pg_catalog.format('GRANT CONNECT ON DATABASE %I TO app_clip', pg_catalog.current_database());
    EXECUTE pg_catalog.format('GRANT CONNECT ON DATABASE %I TO app_chat_collector', pg_catalog.current_database());
    EXECUTE pg_catalog.format('GRANT CONNECT ON DATABASE %I TO app_chat_detector', pg_catalog.current_database());
END $do$;
