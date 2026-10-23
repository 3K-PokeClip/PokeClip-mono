-- 40 확인 — 10 ~ 30 이 만든 상태를 단언한다. 아무것도 바꾸지 않는다.
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 40_verify.sql (마스터 사용자). 모두 통과하면 VERIFY OK 행이 나오고,
-- 하나라도 틀리면 "verify failed: <식별자>, <식별자>" 로 실패한다.
--
-- 식별자:
--   role:<역할>                     media_owner 는 NOLOGIN, 두 계정은 LOGIN 이고 SUPERUSER · CREATEDB ·
--                                   CREATEROLE · REPLICATION · BYPASSRLS 가 없다
--   media_indexer_connection_limit  연결 상한 20
--   media_indexer_statement_timeout 역할 단위 statement_timeout = 60s
--   rds_iam:<계정>                  계정이 rds_iam 에 속한다
--   membership_<계정>               계정의 직접 멤버십이 정확히 {rds_iam} 이다(media_owner · rds_superuser ·
--                                   pg_write_all_data 등의 멤버면 SET ROLE · 상속으로 표를 지울 수 있다)
--   members_media_owner             media_owner 의 멤버는 두 계정을 빼면 접속 신분(마스터) 하나뿐이다
--   schema_create_<계정>            계정이 public 스키마에 CREATE 를 갖지 않는다(직접 부여 포함)
--   public_create_revoked           PUBLIC 이 public 스키마에 CREATE 를 갖지 않는다
--   media_owner_schema              media_owner 가 public 스키마에 USAGE · CREATE 를 가진다
--   owner:<표 · 함수>               소유자가 media_owner 다(없으면 이것이 실패한다)
--   privilege:<역할>:<표>:<권한>    표 단위 권한이 30 의 목록과 같다(PUBLIC 은 아무것도 없다)
--   column_update:media_janitor:<열> janitor 의 열 단위 UPDATE 는 local_path 하나뿐이다
--   advisory_xact_lock_media_indexer media_indexer 가 pg_advisory_xact_lock(bigint) 을 실행할 수 있다
--
-- 마지막 단언은 사이드카 컷오프 잠금(media/internal/index/store.go 의 lockCutoffStreamSQL)의 전제다.
-- 권한 정본(media/internal/pgtest/role.go)은 「advisory 잠금은 PUBLIC 기본으로 충분」 이라 GRANT 를 두지
-- 않는다. 1단-나가 PUBLIC 에서 회수하면서 media_indexer 에 다시 주는 것을 잊으면 여기서 깨진다(K7 · H3).
-- 10 은 남는 멤버십 · 직접 부여를 회수하지 않는다. 무엇이 왜 붙어 있는지 모르므로 여기서 실패로 드러내고
-- 운영자가 판단한다.
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

DO $do$
DECLARE
    failed text[];
BEGIN
    WITH
    tables(t) AS (
        VALUES ('stream_segments'), ('stream_sessions'), ('stream_cutoffs'), ('stream_published_gaps')
    ),
    granted(r, t, p) AS (
        VALUES ('media_indexer', 'stream_segments', 'SELECT'),
               ('media_indexer', 'stream_segments', 'INSERT'),
               ('media_indexer', 'stream_segments', 'UPDATE'),
               ('media_indexer', 'stream_sessions', 'SELECT'),
               ('media_indexer', 'stream_sessions', 'INSERT'),
               ('media_indexer', 'stream_sessions', 'UPDATE'),
               ('media_indexer', 'stream_cutoffs', 'SELECT'),
               ('media_indexer', 'stream_cutoffs', 'INSERT'),
               ('media_indexer', 'stream_published_gaps', 'SELECT'),
               ('media_indexer', 'stream_published_gaps', 'INSERT'),
               ('media_indexer', 'stream_published_gaps', 'UPDATE'),
               ('media_janitor', 'stream_segments', 'SELECT'),
               ('media_janitor', 'stream_sessions', 'SELECT'),
               ('media_janitor', 'stream_cutoffs', 'SELECT')
    ),
    matrix(r, t, p) AS (
        SELECT r, t, p
          FROM unnest(ARRAY['media_indexer', 'media_janitor', 'public']) AS r,
               tables,
               unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER']) AS p
         WHERE to_regclass('public.' || t) IS NOT NULL
    ),
    checks(id, ok) AS (
        SELECT 'role:media_owner',
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_owner' AND NOT rolcanlogin)
        UNION ALL
        SELECT 'role:' || a,
               EXISTS (SELECT 1 FROM pg_roles
                        WHERE rolname = a AND rolcanlogin
                          AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
          FROM unnest(ARRAY['media_indexer', 'media_janitor']) AS a
        UNION ALL
        SELECT 'media_indexer_connection_limit',
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_indexer' AND rolconnlimit = 20)
        UNION ALL
        SELECT 'media_indexer_statement_timeout',
               EXISTS (SELECT 1 FROM pg_db_role_setting s JOIN pg_roles r ON r.oid = s.setrole
                        WHERE r.rolname = 'media_indexer' AND s.setdatabase = 0
                          AND 'statement_timeout=60s' = ANY (s.setconfig))
        UNION ALL
        SELECT 'rds_iam:' || a,
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a) AND pg_has_role(a, 'rds_iam', 'MEMBER')
          FROM unnest(ARRAY['media_indexer', 'media_janitor']) AS a
        UNION ALL
        SELECT 'membership_' || a,
               ARRAY(SELECT DISTINCT pg_get_userbyid(m.roleid)::text
                       FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                      WHERE r.rolname = a ORDER BY 1) = ARRAY['rds_iam']
          FROM unnest(ARRAY['media_indexer', 'media_janitor']) AS a
        UNION ALL
        SELECT 'members_media_owner',
               ARRAY(SELECT DISTINCT pg_get_userbyid(m.member)::text
                       FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
                      WHERE r.rolname = 'media_owner'
                        AND pg_get_userbyid(m.member) NOT IN ('media_indexer', 'media_janitor')
                      ORDER BY 1) = ARRAY[current_user::text]
        UNION ALL
        SELECT 'schema_create_' || a,
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a)
               AND NOT has_schema_privilege(a, 'public', 'CREATE')
          FROM unnest(ARRAY['media_indexer', 'media_janitor']) AS a
        UNION ALL
        SELECT 'public_create_revoked', NOT has_schema_privilege('public', 'public', 'CREATE')
        UNION ALL
        SELECT 'media_owner_schema',
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_owner')
               AND has_schema_privilege('media_owner', 'public', 'USAGE')
               AND has_schema_privilege('media_owner', 'public', 'CREATE')
        UNION ALL
        SELECT 'owner:' || t,
               EXISTS (SELECT 1 FROM pg_class
                        WHERE oid = to_regclass('public.' || t) AND pg_get_userbyid(relowner) = 'media_owner')
          FROM tables
        UNION ALL
        SELECT 'owner:stream_segments_immutable_axes',
               EXISTS (SELECT 1 FROM pg_proc
                        WHERE oid = to_regprocedure('public.stream_segments_immutable_axes()')
                          AND pg_get_userbyid(proowner) = 'media_owner')
        UNION ALL
        SELECT format('privilege:%s:%s:%s', m.r, m.t, m.p),
               has_table_privilege(m.r, 'public.' || m.t, m.p)
               = EXISTS (SELECT 1 FROM granted g WHERE (g.r, g.t, g.p) = (m.r, m.t, m.p))
          FROM matrix m
         WHERE m.r = 'public' OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = m.r)
        UNION ALL
        SELECT 'column_update:media_janitor:' || attname,
               has_column_privilege('media_janitor', 'public.stream_segments', attname, 'UPDATE')
               = (attname = 'local_path')
          FROM pg_attribute
         WHERE attrelid = to_regclass('public.stream_segments') AND attnum > 0 AND NOT attisdropped
           AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_janitor')
        UNION ALL
        SELECT 'advisory_xact_lock_media_indexer',
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_indexer')
               AND has_function_privilege('media_indexer', 'pg_advisory_xact_lock(bigint)', 'EXECUTE')
    )
    SELECT coalesce(array_agg(id ORDER BY id), '{}') INTO failed FROM checks WHERE ok IS NOT TRUE;

    IF cardinality(failed) > 0 THEN
        RAISE EXCEPTION 'verify failed: %', array_to_string(failed, ', ');
    END IF;
END $do$;

SELECT 'VERIFY OK' AS verify;
