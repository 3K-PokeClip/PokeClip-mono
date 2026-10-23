-- 00 사전 점검 — 적용 전에 이 DB 와 접속 신분이 전제를 갖췄는지 본다. 아무것도 바꾸지 않는다.
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 00_preflight.sql (마스터 사용자 · verify-full 접속).
-- 실패한 점검을 모아 한 번에 알린다: "preflight failed: <식별자>, <식별자>". 통과하면 PREFLIGHT OK 행이 나온다.
--
-- 식별자:
--   version              서버 판이 17 이상
--   rds.force_ssl        파라미터 그룹의 rds.force_ssl = 1
--   tls                  이 연결이 TLS
--   rds_iam              IAM 인증 역할 rds_iam 이 있다
--   master_not_superuser 접속 신분이 superuser 가 아니다(RDS 마스터 모양)
--   master_createrole    접속 신분이 CREATEROLE 을 가진다
--   db_owner             이 DB 의 소유자가 접속 신분이다(public 스키마 소유 = pg_database_owner)
--   public_create        PUBLIC 이 public 스키마에 CREATE 를 갖지 않는다(PG15+ 기본값)
--
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

DO $do$
DECLARE
    failed text[] := '{}';
    me     pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO me FROM pg_roles WHERE rolname = current_user;

    IF current_setting('server_version_num')::int < 170000 THEN
        failed := failed || 'version'::text;
    END IF;
    IF coalesce(current_setting('rds.force_ssl', true), '') NOT IN ('1', 'on') THEN
        failed := failed || 'rds.force_ssl'::text;
    END IF;
    IF NOT coalesce((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), false) THEN
        failed := failed || 'tls'::text;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rds_iam') THEN
        failed := failed || 'rds_iam'::text;
    END IF;
    IF me.rolsuper THEN
        failed := failed || 'master_not_superuser'::text;
    END IF;
    IF NOT me.rolcreaterole THEN
        failed := failed || 'master_createrole'::text;
    END IF;
    IF (SELECT datdba FROM pg_database WHERE datname = current_database()) <> me.oid THEN
        failed := failed || 'db_owner'::text;
    END IF;
    IF has_schema_privilege('public', 'public', 'CREATE') THEN
        failed := failed || 'public_create'::text;
    END IF;

    IF cardinality(failed) > 0 THEN
        RAISE EXCEPTION 'preflight failed: %', array_to_string(failed, ', ');
    END IF;
END $do$;

SELECT 'PREFLIGHT OK' AS preflight;
