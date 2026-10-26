-- 00 사전 점검 — 적용 전에 이 DB 와 접속 신분이 전제를 갖췄는지 본다. 아무것도 바꾸지 않는다.
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 00_preflight.sql (마스터 사용자 · verify-full 접속).
-- 실패한 점검을 모아 한 번에 알린다: "preflight failed: <식별자>, <식별자>". 통과하면 PREFLIGHT OK 행이 나온다.
--
-- 식별자:
--   version              서버 판이 17 이상
--   rds.force_ssl        세션에 보이면 1 또는 on 이다. RDS PG 17 은 세션에 보이지
--                        않음 — 강제는 파라미터 그룹 계약(rds.tf) · 이 연결 TLS 는
--                        tls 점검
--   tls                  이 연결이 TLS
--   rds_iam              IAM 인증 역할 rds_iam 이 있다
--   master_not_superuser 접속 신분이 superuser 가 아니다(RDS 마스터 모양)
--   master_createrole    접속 신분이 CREATEROLE 을 가진다
--   db_owner             이 DB 의 소유자가 접속 신분이다(public 스키마 소유 = pg_database_owner)
--   public_create        PUBLIC 이 public 스키마에 CREATE 를 갖지 않는다(PG15+ 기본값)
--
-- 내장 함수는 pg_catalog 로 한정한다 — 앱 사용자 넷이 public 에 심은 같은 이름 함수가 마스터 신분으로 돌지
-- 않게(search_path 가로채기, media/README.md). 배열에 덧붙이기는 연산자 || 대신 array_append 다(text[] || text
-- 는 pg_catalog 에 다형 판뿐이라 public 의 정확한 판이 먼저 골린다).
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

DO $do$
DECLARE
    failed text[] := '{}';
    me     pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO me FROM pg_roles WHERE rolname = current_user;

    IF pg_catalog.current_setting('server_version_num')::int < 170000 THEN
        failed := pg_catalog.array_append(failed, 'version');
    END IF;
    -- 보이지 않으면(NULL · 빈 값) 통과한다 — 식별자 설명 참조.
    IF coalesce(pg_catalog.current_setting('rds.force_ssl', true), '') NOT IN ('', '1', 'on') THEN
        failed := pg_catalog.array_append(failed, 'rds.force_ssl');
    END IF;
    IF NOT coalesce((SELECT ssl FROM pg_stat_ssl WHERE pid = pg_catalog.pg_backend_pid()), false) THEN
        failed := pg_catalog.array_append(failed, 'tls');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rds_iam') THEN
        failed := pg_catalog.array_append(failed, 'rds_iam');
    END IF;
    IF me.rolsuper THEN
        failed := pg_catalog.array_append(failed, 'master_not_superuser');
    END IF;
    IF NOT me.rolcreaterole THEN
        failed := pg_catalog.array_append(failed, 'master_createrole');
    END IF;
    IF (SELECT datdba FROM pg_database WHERE datname = pg_catalog.current_database()) <> me.oid THEN
        failed := pg_catalog.array_append(failed, 'db_owner');
    END IF;
    IF pg_catalog.has_schema_privilege('public', 'public', 'CREATE') THEN
        failed := pg_catalog.array_append(failed, 'public_create');
    END IF;

    IF pg_catalog.cardinality(failed) > 0 THEN
        RAISE EXCEPTION 'preflight failed: %', pg_catalog.array_to_string(failed, ', ');
    END IF;
END $do$;

SELECT 'PREFLIGHT OK' AS preflight;
