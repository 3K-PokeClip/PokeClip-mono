-- 40 확인 — 10 ~ 30 이 만든 상태를 단언한다. 아무것도 바꾸지 않는다.
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 40_verify.sql (마스터 사용자). 모두 통과하면 VERIFY OK 행이 나오고,
-- 하나라도 틀리면 "verify failed: <식별자>, <식별자>" 로 실패한다.
--
-- 계정은 media 두 계정(media_indexer · media_janitor)과 앱 사용자 넷(app_auth · app_clip · app_chat_collector ·
-- app_chat_detector)이다. 앱 사용자가 아직 없으면(10 을 돌리기 전) 그 계정의 단언이 실패한다.
--
-- 식별자:
--   role:<역할>                     media_owner 는 NOLOGIN, 계정은 LOGIN 이고 SUPERUSER · CREATEDB ·
--                                   CREATEROLE · REPLICATION · BYPASSRLS 가 없다
--   media_indexer_connection_limit  연결 상한 20
--   media_indexer_statement_timeout 역할 단위 statement_timeout = 60s
--   rds_iam:<계정>                  계정이 rds_iam 에 속한다
--   membership_<계정>               계정의 직접 멤버십이 정확히 {rds_iam} 이다(media_owner · rds_superuser ·
--                                   pg_write_all_data 등의 멤버면 SET ROLE · 상속으로 표를 지울 수 있다)
--   members_media_owner             media_owner 의 멤버는 계정을 빼면 접속 신분(마스터) 하나뿐이다(계정은
--                                   membership_<계정> 이 잡는다)
--   schema_create_<계정>            계정이 public 스키마 CREATE 를 갖지 않는다(직접 부여 포함) — 앱 사용자도 자기
--                                   표는 자기 스키마에 만든다
--   app_schema:<스키마>             앱 스키마(app_auth · app_clip · app_chat_collector · app_chat_detector)가 있고
--                                   소유자가 접속 신분(마스터)이다(K9)
--   schema_priv:<계정>:<스키마>:<권한>
--                                   있는 앱 스키마마다 계정의 USAGE · CREATE 가 기대와 같다 — 참은 앱 사용자의
--                                   자기 스키마 둘씩과 app_chat_detector 의 app_chat_collector USAGE 하나다
--   public_owned_by_app:<계정>      public 의 표 · 함수 · 형(pg_class · pg_proc · pg_type) 가운데 그 계정 소유가 없다
--   schema_usage_<계정>             계정이 public 스키마 USAGE 를 가진다(PUBLIC 기본) — 없으면 표 권한이 있어도
--                                   stream_* · 자기 표의 이름을 풀지 못한다
--   public_create_revoked           PUBLIC 이 public 스키마에 CREATE 를 갖지 않는다
--   media_owner_schema              media_owner 가 public 스키마에 USAGE · CREATE 를 가진다
--   owner:<표 · 함수>               소유자가 media_owner 다(없으면 이것이 실패한다)
--   privilege:<역할>:<표>:<권한>    표 단위 권한이 30 의 목록과 같다(PUBLIC 과 교차 권한이 없는 앱 사용자 둘은
--                                   stream_* 에 아무것도 없다). 권한은 PG17 의
--                                   MAINTAIN 까지 여덟이고, 끝에 +grant_option 이 붙은 것은 재부여 권한이다(모두 없다)
--   column:<역할>:<표>.<열>:<권한>  열 단위 권한이 표 단위 권한과 같다 — 예외는 janitor 의 stream_segments.local_path
--                                   UPDATE 하나다(+grant_option 은 위와 같다)
--   connect:<계정>                  계정이 이 DB 에 CONNECT 할 수 있다(10 이 계정마다 준다)
--   public_connect_revoked          PUBLIC 이 이 DB 에 CONNECT 를 갖지 않는다
--   public_temp_revoked             PUBLIC 이 이 DB 에 TEMP 를 갖지 않는다
--   temp:<계정>                     계정이 이 DB 에 TEMP 를 갖지 않는다
--   db_create:<계정>                계정이 이 DB 에 CREATE 를 갖지 않는다(남의 "$user" 스키마를 먼저 만들지 못하게)
--   advisory_xact_lock_media_indexer media_indexer 가 pg_advisory_xact_lock(bigint) 을 실행할 수 있다
--
-- 마지막 단언은 사이드카 컷오프 잠금(media/internal/index/store.go 의 lockCutoffStreamSQL)의 전제다.
-- 권한 정본(media/internal/pgtest/role.go)은 「advisory 잠금은 PUBLIC 기본으로 충분」 이라 GRANT 를 두지
-- 않는다. K7 (가) — PUBLIC 에서 회수하지 않는다(kty 2026-10-24). 회수하는 날 media_indexer 에 다시 주지 않으면
-- 여기서 깨진다(H3). CONNECT 는 PUBLIC 에서 거두고 10 이 계정마다 준다 — 10 의 부여가 빠지면 connect 가 깨진다.
-- schema_usage(public USAGE)는 PUBLIC 기본 그대로라, PUBLIC 에서 회수하면 여기서 깨진다.
-- 10 은 남는 멤버십 · 직접 부여를 회수하지 않는다. 무엇이 왜 붙어 있는지 모르므로 여기서 실패로 드러내고
-- 운영자가 판단한다.
--
-- 단언하지 않는 것: 다른 DB(postgres · template1)의 CONNECT · TEMP 는 PUBLIC 기본으로 남는다(K12 — 이 DB 의
-- TEMP 는 위에서 단언한다). 비밀번호 없음도 보지 못한다 — 마스터는 pg_authid 를 읽지 못하고
-- pg_roles.rolpassword 는 늘 ******** 다. 앱 사용자가 자기 표에 갖는 권한과 app_chat_detector 의 chat_messages
-- SELECT(수집 서버 Flyway V316 이 준다)도 보지 않는다 — 표 행렬은 stream_* 표만 본다.
--
-- 내장 함수는 pg_catalog 로 한정하고 연산자는 pg_catalog 에 정확한 판이 있는 형으로 맞춘다(oid 열과 비교하는
-- regclass · regprocedure · 정수 상수는 ::oid). 배열 비교(text[] = text[])는 pg_catalog 에 다형 판뿐이라
-- OPERATOR(pg_catalog.=) 로 쓴다. 맞추지 않으면 public CREATE 를 다시 연 DB 에서 누군가 public 에 심은 같은
-- 이름의 판이 마스터 신분으로 돈다(search_path 가로채기 · 심층 방어, media/README.md).
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

DO $do$
DECLARE
    failed text[];
BEGIN
    WITH
    accounts(a) AS (
        VALUES ('media_indexer'), ('media_janitor'), ('app_auth'), ('app_clip'), ('app_chat_collector'),
               ('app_chat_detector')
    ),
    app_schemas(s) AS (
        VALUES ('app_auth'), ('app_clip'), ('app_chat_collector'), ('app_chat_detector')
    ),
    -- 앱 스키마에서 참이어야 하는 (계정, 스키마, 권한)이다. 행렬의 나머지는 모두 거짓이다.
    schema_granted(a, s, p) AS (
        VALUES ('app_auth', 'app_auth', 'USAGE'),
               ('app_auth', 'app_auth', 'CREATE'),
               ('app_clip', 'app_clip', 'USAGE'),
               ('app_clip', 'app_clip', 'CREATE'),
               ('app_chat_collector', 'app_chat_collector', 'USAGE'),
               ('app_chat_collector', 'app_chat_collector', 'CREATE'),
               ('app_chat_detector', 'app_chat_detector', 'USAGE'),
               ('app_chat_detector', 'app_chat_detector', 'CREATE'),
               ('app_chat_detector', 'app_chat_collector', 'USAGE')
    ),
    -- 있는 계정 · 있는 앱 스키마만 펼친다 — 없는 스키마를 has_schema_privilege 에 넘기면 3F000 으로 40 전체가
    -- 깨진다. 없는 스키마는 app_schema:<스키마> 하나로 드러난다.
    schema_matrix(a, s, p) AS (
        SELECT a, s, p
          FROM accounts,
               app_schemas,
               pg_catalog.unnest(ARRAY['USAGE', 'CREATE']) AS p
         WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a)
           AND EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = s)
    ),
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
               ('media_janitor', 'stream_cutoffs', 'SELECT'),
               ('app_clip', 'stream_segments', 'SELECT'),
               ('app_clip', 'stream_segments', 'DELETE'),
               ('app_clip', 'stream_sessions', 'SELECT'),
               ('app_chat_collector', 'stream_segments', 'SELECT')
    ),
    grantees(r) AS (
        SELECT a FROM accounts WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a)
        UNION ALL
        SELECT 'public'
    ),
    -- go = 재부여 권한(WITH GRANT OPTION). 30 은 하나도 주지 않는다.
    matrix(r, t, p, go) AS (
        SELECT r, t, p, go
          FROM grantees,
               tables,
               pg_catalog.unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER',
                                       'MAINTAIN']) AS p,
               pg_catalog.unnest(ARRAY[false, true]) AS go
         WHERE pg_catalog.to_regclass('public.' || t) IS NOT NULL
    ),
    column_matrix(r, t, c, p, go) AS (
        SELECT r, t, a.attname::text, p, go
          FROM grantees,
               tables
               JOIN pg_attribute a
                 ON a.attrelid = pg_catalog.to_regclass('public.' || t)::oid AND a.attnum > 0 AND NOT a.attisdropped,
               pg_catalog.unnest(ARRAY['SELECT', 'INSERT', 'UPDATE', 'REFERENCES']) AS p,
               pg_catalog.unnest(ARRAY[false, true]) AS go
    ),
    checks(id, ok) AS (
        SELECT 'role:media_owner',
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_owner' AND NOT rolcanlogin)
        UNION ALL
        SELECT 'role:' || a,
               EXISTS (SELECT 1 FROM pg_roles
                        WHERE rolname = a AND rolcanlogin
                          AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
          FROM accounts
        UNION ALL
        SELECT 'media_indexer_connection_limit',
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_indexer' AND rolconnlimit = 20)
        UNION ALL
        SELECT 'media_indexer_statement_timeout',
               EXISTS (SELECT 1 FROM pg_db_role_setting s JOIN pg_roles r ON r.oid = s.setrole
                        WHERE r.rolname = 'media_indexer' AND s.setdatabase = 0::oid
                          AND 'statement_timeout=60s' = ANY (s.setconfig))
        UNION ALL
        SELECT 'rds_iam:' || a,
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a) AND pg_catalog.pg_has_role(a, 'rds_iam', 'MEMBER')
          FROM accounts
        UNION ALL
        SELECT 'membership_' || a,
               ARRAY(SELECT DISTINCT pg_catalog.pg_get_userbyid(m.roleid)::text
                       FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                      WHERE r.rolname = a ORDER BY 1) OPERATOR(pg_catalog.=) ARRAY['rds_iam']
          FROM accounts
        UNION ALL
        SELECT 'members_media_owner',
               ARRAY(SELECT DISTINCT pg_catalog.pg_get_userbyid(m.member)::text
                       FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
                      WHERE r.rolname = 'media_owner'
                        AND pg_catalog.pg_get_userbyid(m.member) NOT IN (SELECT a FROM accounts)
                      ORDER BY 1) OPERATOR(pg_catalog.=) ARRAY[current_user::text]
        UNION ALL
        SELECT 'schema_create_' || a,
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a)
               AND NOT pg_catalog.has_schema_privilege(a, 'public', 'CREATE')
          FROM accounts
        UNION ALL
        SELECT 'schema_usage_' || a,
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a)
               AND pg_catalog.has_schema_privilege(a, 'public', 'USAGE')
          FROM accounts
        UNION ALL
        SELECT 'public_create_revoked', NOT pg_catalog.has_schema_privilege('public', 'public', 'CREATE')
        UNION ALL
        SELECT 'media_owner_schema',
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_owner')
               AND pg_catalog.has_schema_privilege('media_owner', 'public', 'USAGE')
               AND pg_catalog.has_schema_privilege('media_owner', 'public', 'CREATE')
        UNION ALL
        SELECT 'owner:' || t,
               EXISTS (SELECT 1 FROM pg_class
                        WHERE oid = pg_catalog.to_regclass('public.' || t)::oid
                          AND pg_catalog.pg_get_userbyid(relowner) = 'media_owner')
          FROM tables
        UNION ALL
        SELECT 'owner:stream_segments_immutable_axes',
               EXISTS (SELECT 1 FROM pg_proc
                        WHERE oid = pg_catalog.to_regprocedure('public.stream_segments_immutable_axes()')::oid
                          AND pg_catalog.pg_get_userbyid(proowner) = 'media_owner')
        UNION ALL
        SELECT pg_catalog.format('privilege:%s:%s:%s%s', m.r, m.t, m.p,
                                 CASE WHEN m.go THEN '+grant_option' ELSE '' END),
               pg_catalog.has_table_privilege(m.r, 'public.' || m.t,
                                              m.p || CASE WHEN m.go THEN ' WITH GRANT OPTION' ELSE '' END)
               = (NOT m.go AND EXISTS (SELECT 1 FROM granted g WHERE (g.r, g.t, g.p) = (m.r, m.t, m.p)))
          FROM matrix m
        UNION ALL
        -- 표 단위 권한이 있으면 열 단위도 참이다. 그래서 기대값은 표 단위 결과이고, 어긋나면 열에만 준 권한이다.
        SELECT pg_catalog.format('column:%s:%s.%s:%s%s', c.r, c.t, c.c, c.p,
                                 CASE WHEN c.go THEN '+grant_option' ELSE '' END),
               pg_catalog.has_column_privilege(c.r, 'public.' || c.t, c.c,
                                               c.p || CASE WHEN c.go THEN ' WITH GRANT OPTION' ELSE '' END)
               = (pg_catalog.has_table_privilege(c.r, 'public.' || c.t,
                                                 c.p || CASE WHEN c.go THEN ' WITH GRANT OPTION' ELSE '' END)
                  OR (c.r, c.t, c.c, c.p, c.go) = ('media_janitor', 'stream_segments', 'local_path', 'UPDATE', false))
          FROM column_matrix c
        UNION ALL
        SELECT 'connect:' || a,
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a)
               AND pg_catalog.has_database_privilege(a, pg_catalog.current_database(), 'CONNECT')
          FROM accounts
        UNION ALL
        SELECT 'public_connect_revoked',
               NOT pg_catalog.has_database_privilege('public', pg_catalog.current_database(), 'CONNECT')
        UNION ALL
        SELECT 'public_temp_revoked',
               NOT pg_catalog.has_database_privilege('public', pg_catalog.current_database(), 'TEMPORARY')
        UNION ALL
        SELECT 'temp:' || a,
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a)
               AND NOT pg_catalog.has_database_privilege(a, pg_catalog.current_database(), 'TEMPORARY')
          FROM accounts
        UNION ALL
        SELECT 'db_create:' || a,
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = a)
               AND NOT pg_catalog.has_database_privilege(a, pg_catalog.current_database(), 'CREATE')
          FROM accounts
        UNION ALL
        SELECT 'app_schema:' || s,
               EXISTS (SELECT 1 FROM pg_namespace
                        WHERE nspname = s AND pg_catalog.pg_get_userbyid(nspowner) = current_user)
          FROM app_schemas
        UNION ALL
        SELECT pg_catalog.format('schema_priv:%s:%s:%s', m.a, m.s, m.p),
               pg_catalog.has_schema_privilege(m.a, m.s, m.p)
               = EXISTS (SELECT 1 FROM schema_granted g WHERE (g.a, g.s, g.p) = (m.a, m.s, m.p))
          FROM schema_matrix m
        UNION ALL
        SELECT 'public_owned_by_app:' || a,
               NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = 'public' AND pg_catalog.pg_get_userbyid(c.relowner) = a)
               AND NOT EXISTS (SELECT 1 FROM pg_proc f JOIN pg_namespace n ON n.oid = f.pronamespace
                                WHERE n.nspname = 'public' AND pg_catalog.pg_get_userbyid(f.proowner) = a)
               AND NOT EXISTS (SELECT 1 FROM pg_type y JOIN pg_namespace n ON n.oid = y.typnamespace
                                WHERE n.nspname = 'public' AND pg_catalog.pg_get_userbyid(y.typowner) = a)
          FROM accounts
        UNION ALL
        SELECT 'advisory_xact_lock_media_indexer',
               EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'media_indexer')
               AND pg_catalog.has_function_privilege('media_indexer', 'pg_catalog.pg_advisory_xact_lock(bigint)',
                                                     'EXECUTE')
    )
    SELECT coalesce(pg_catalog.array_agg(id ORDER BY id), '{}') INTO failed FROM checks WHERE ok IS NOT TRUE;

    IF pg_catalog.cardinality(failed) > 0 THEN
        RAISE EXCEPTION 'verify failed: %', pg_catalog.array_to_string(failed, ', ');
    END IF;
END $do$;

SELECT 'VERIFY OK' AS verify;
