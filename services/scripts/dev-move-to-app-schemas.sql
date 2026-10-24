-- dev 공유 DB 의 앱 표를 public 에서 서버마다 자기 스키마(이름 = 운영 DB 계정)로 옮긴다(POK-428 · 설계 K11 가).
--
-- 배포 잡(services-deploy.yml)이 dev-move-to-app-schemas.sh 로 부른다 — 서버 넷 · 일꾼 둘을 세우고 public 을
-- 백업한 뒤에 돈다. psql -1 -v ON_ERROR_STOP=1 로 돌리고 JDBC 로 돌려도 같다: 파일 전체가 DO 블록 하나라
-- 어디서 실패하든 아무것도 바뀌지 않는다. psql 변수를 쓰지 않는다.
--
-- 서버마다 블록 하나이고 그 서버의 이력 표가 public 에 있을 때만 돈다(이미 옮긴 DB · 그 서버가 뜬 적 없는 DB 는
-- 건너뛴다 — 다시 돌려도 같은 상태). 목록의 표 · 객체가 하나라도 없으면 그 ALTER 가 42P01 로 실패한다 — 그것이
-- 「목록이 다 있다」 의 단언이다. 자기 스키마에 같은 이름이 이미 있어도(새 서버가 먼저 떠 빈 표를 만든 경우) ALTER
-- 가 실패한다. 표를 옮기면 색인 · 제약 · 열 소유 시퀀스(IDENTITY) · 트리거가 따라간다. clip 의 독립 시퀀스와
-- 트리거 함수(V202)는 따로 옮긴다.
--
-- 목록 정본은 서버마다 Flyway 마이그레이션의 CREATE TABLE 이다. 마이그레이션이 표를 더하면 이 목록에도 더한다 —
-- 빠뜨리면 끝 검사가 public 에 남은 표로 잡는다(되돌리기는 dev-move-back-to-public.sql).
--
-- 끝 검사: public 에 남아도 되는 것은 media(1번) 표 넷과 그 트리거 함수 stream_segments_immutable_axes 뿐이다
-- (media/internal/index/ddl.go). 그 밖의 표 · 뷰 · 시퀀스 · 함수가 남으면 실패한다 — 독립 시퀀스나 함수를 빠뜨려도
-- 표만 보는 검사는 통과하기 때문이다.
DO $do$
DECLARE
    leftover text;
BEGIN
    -- auth: 표 13 · 이력 표 1
    IF pg_catalog.to_regclass('public.flyway_schema_history_auth') IS NOT NULL THEN
        CREATE SCHEMA IF NOT EXISTS app_auth;
        ALTER TABLE public.flyway_schema_history_auth SET SCHEMA app_auth;
        ALTER TABLE public.audio_track_labels SET SCHEMA app_auth;
        ALTER TABLE public.chzzk_channel_links SET SCHEMA app_auth;
        ALTER TABLE public.editor_delegations SET SCHEMA app_auth;
        ALTER TABLE public.editor_invitations SET SCHEMA app_auth;
        ALTER TABLE public.pairing_codes SET SCHEMA app_auth;
        ALTER TABLE public.pairing_exchange_attempts SET SCHEMA app_auth;
        ALTER TABLE public.refresh_tokens SET SCHEMA app_auth;
        ALTER TABLE public.secrets SET SCHEMA app_auth;
        ALTER TABLE public.soop_channel_links SET SCHEMA app_auth;
        ALTER TABLE public.stream_keys SET SCHEMA app_auth;
        ALTER TABLE public.users SET SCHEMA app_auth;
        ALTER TABLE public.withdrawal_purge_jobs SET SCHEMA app_auth;
        ALTER TABLE public.youtube_channel_links SET SCHEMA app_auth;
    END IF;

    -- clip: 표 13 · 이력 표 1 · 독립 시퀀스 1 · 함수 1(V202)
    IF pg_catalog.to_regclass('public.flyway_schema_history_clip') IS NOT NULL THEN
        CREATE SCHEMA IF NOT EXISTS app_clip;
        ALTER TABLE public.flyway_schema_history_clip SET SCHEMA app_clip;
        ALTER TABLE public.broadcast_events SET SCHEMA app_clip;
        ALTER TABLE public.broadcast_platforms SET SCHEMA app_clip;
        ALTER TABLE public.broadcasts SET SCHEMA app_clip;
        ALTER TABLE public.clip_uploads SET SCHEMA app_clip;
        ALTER TABLE public.clips SET SCHEMA app_clip;
        ALTER TABLE public.jump_card_actions SET SCHEMA app_clip;
        ALTER TABLE public.jump_cards SET SCHEMA app_clip;
        ALTER TABLE public.purged_streamers SET SCHEMA app_clip;
        ALTER TABLE public.recipes SET SCHEMA app_clip;
        ALTER TABLE public.render_job_events SET SCHEMA app_clip;
        ALTER TABLE public.render_jobs SET SCHEMA app_clip;
        ALTER TABLE public.thumbnails SET SCHEMA app_clip;
        ALTER TABLE public.upload_requests SET SCHEMA app_clip;
        ALTER SEQUENCE public.jump_card_event_seq SET SCHEMA app_clip;
        ALTER FUNCTION public.jump_cards_touch() SET SCHEMA app_clip;
    END IF;

    -- chat-collector: 표 6 · 이력 표 1
    IF pg_catalog.to_regclass('public.flyway_schema_history_chat') IS NOT NULL THEN
        CREATE SCHEMA IF NOT EXISTS app_chat_collector;
        ALTER TABLE public.flyway_schema_history_chat SET SCHEMA app_chat_collector;
        ALTER TABLE public.broadcast_info SET SCHEMA app_chat_collector;
        ALTER TABLE public.chat_donations SET SCHEMA app_chat_collector;
        ALTER TABLE public.chat_ended_streams SET SCHEMA app_chat_collector;
        ALTER TABLE public.chat_ingest_keys SET SCHEMA app_chat_collector;
        ALTER TABLE public.chat_messages SET SCHEMA app_chat_collector;
        ALTER TABLE public.purged_channels SET SCHEMA app_chat_collector;
    END IF;

    -- chat-detector: 표 1 · 이력 표 1
    IF pg_catalog.to_regclass('public.flyway_schema_history_chat_detector') IS NOT NULL THEN
        CREATE SCHEMA IF NOT EXISTS app_chat_detector;
        ALTER TABLE public.flyway_schema_history_chat_detector SET SCHEMA app_chat_detector;
        ALTER TABLE public.chat_metrics SET SCHEMA app_chat_detector;
    END IF;

    SELECT pg_catalog.string_agg(c.relname::text || '(' || c.relkind::text || ')', ', ' ORDER BY c.relname)
      INTO leftover
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND ((c.relkind IN ('r', 'p', 'v', 'm')
             AND c.relname NOT IN ('stream_segments', 'stream_sessions', 'stream_cutoffs', 'stream_published_gaps'))
            OR c.relkind = 'S');
    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'schema move: unlisted relations left in public: %', leftover;
    END IF;
    SELECT pg_catalog.string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text)
      INTO leftover
      FROM pg_catalog.pg_proc p
      JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname <> 'stream_segments_immutable_axes';
    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'schema move: unlisted functions left in public: %', leftover;
    END IF;
END $do$;
