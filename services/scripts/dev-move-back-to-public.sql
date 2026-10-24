-- dev 공유 DB 의 앱 표를 서버마다 자기 스키마에서 public 으로 되돌린다(POK-428 · 계획 H1 — 앞방향은
-- dev-move-to-app-schemas.sql).
--
-- 이 PR 을 revert 하기 **전에** 돌린다. 순서(services/README.md 「dev 앱별 스키마 이전과 되돌리기」): 배포 배타
-- 걸기 → 진행 중 배포 끝남 → 서버 넷 · 일꾼 정지 → pg_dump 백업 → 이 파일 → 확인 → revert PR 머지 → revert 배포
-- 승인 · 확인 → 배타 풀기. 역이전 전에 revert 가 배포되면 옛 Flyway(public · baseline-on-migrate)가 public 에 빈
-- 표를 만들어 자료가 조용히 갈린다.
--
-- 앞방향과 같은 꼴이다: 파일 전체가 DO 블록 하나 · 서버마다 그 서버 이력 표가 자기 스키마에 있을 때만 · 목록의 표가
-- 없거나 public 에 같은 이름이 있으면 ALTER 가 실패한다. 자기 스키마는 지우지 않는다(비어 남는다 — 옛 코드는 보지
-- 않는다).
--
-- 끝 검사: 앱 스키마 넷에 표 · 뷰 · 시퀀스 · 함수가 하나라도 남으면 실패한다(이전 뒤 새 마이그레이션이 자기
-- 스키마에 표를 더하고 이 목록에 안 넣은 경우).
DO $do$
DECLARE
    leftover text;
BEGIN
    -- auth: 표 14 · 이력 표 1
    IF pg_catalog.to_regclass('app_auth.flyway_schema_history_auth') IS NOT NULL THEN
        ALTER TABLE app_auth.flyway_schema_history_auth SET SCHEMA public;
        ALTER TABLE app_auth.audio_track_labels SET SCHEMA public;
        ALTER TABLE app_auth.chzzk_channel_links SET SCHEMA public;
        ALTER TABLE app_auth.editor_delegations SET SCHEMA public;
        ALTER TABLE app_auth.editor_invitations SET SCHEMA public;
        ALTER TABLE app_auth.pairing_codes SET SCHEMA public;
        ALTER TABLE app_auth.pairing_exchange_attempts SET SCHEMA public;
        ALTER TABLE app_auth.refresh_tokens SET SCHEMA public;
        ALTER TABLE app_auth.secrets SET SCHEMA public;
        ALTER TABLE app_auth.soop_channel_links SET SCHEMA public;
        ALTER TABLE app_auth.stream_keys SET SCHEMA public;
        ALTER TABLE app_auth.users SET SCHEMA public;
        ALTER TABLE app_auth.withdrawal_purge_jobs SET SCHEMA public;
        ALTER TABLE app_auth.youtube_channel_links SET SCHEMA public;
        ALTER TABLE app_auth.youtube_video_forget_jobs SET SCHEMA public;
    END IF;

    -- clip: 표 13 · 이력 표 1 · 독립 시퀀스 1 · 함수 1(V202)
    IF pg_catalog.to_regclass('app_clip.flyway_schema_history_clip') IS NOT NULL THEN
        ALTER TABLE app_clip.flyway_schema_history_clip SET SCHEMA public;
        ALTER TABLE app_clip.broadcast_events SET SCHEMA public;
        ALTER TABLE app_clip.broadcast_platforms SET SCHEMA public;
        ALTER TABLE app_clip.broadcasts SET SCHEMA public;
        ALTER TABLE app_clip.clip_uploads SET SCHEMA public;
        ALTER TABLE app_clip.clips SET SCHEMA public;
        ALTER TABLE app_clip.jump_card_actions SET SCHEMA public;
        ALTER TABLE app_clip.jump_cards SET SCHEMA public;
        ALTER TABLE app_clip.purged_streamers SET SCHEMA public;
        ALTER TABLE app_clip.recipes SET SCHEMA public;
        ALTER TABLE app_clip.render_job_events SET SCHEMA public;
        ALTER TABLE app_clip.render_jobs SET SCHEMA public;
        ALTER TABLE app_clip.thumbnails SET SCHEMA public;
        ALTER TABLE app_clip.upload_requests SET SCHEMA public;
        ALTER SEQUENCE app_clip.jump_card_event_seq SET SCHEMA public;
        ALTER FUNCTION app_clip.jump_cards_touch() SET SCHEMA public;
    END IF;

    -- chat-collector: 표 6 · 이력 표 1
    IF pg_catalog.to_regclass('app_chat_collector.flyway_schema_history_chat') IS NOT NULL THEN
        ALTER TABLE app_chat_collector.flyway_schema_history_chat SET SCHEMA public;
        ALTER TABLE app_chat_collector.broadcast_info SET SCHEMA public;
        ALTER TABLE app_chat_collector.chat_donations SET SCHEMA public;
        ALTER TABLE app_chat_collector.chat_ended_streams SET SCHEMA public;
        ALTER TABLE app_chat_collector.chat_ingest_keys SET SCHEMA public;
        ALTER TABLE app_chat_collector.chat_messages SET SCHEMA public;
        ALTER TABLE app_chat_collector.purged_channels SET SCHEMA public;
    END IF;

    -- chat-detector: 표 1 · 이력 표 1
    IF pg_catalog.to_regclass('app_chat_detector.flyway_schema_history_chat_detector') IS NOT NULL THEN
        ALTER TABLE app_chat_detector.flyway_schema_history_chat_detector SET SCHEMA public;
        ALTER TABLE app_chat_detector.chat_metrics SET SCHEMA public;
    END IF;

    SELECT pg_catalog.string_agg(n.nspname::text || '.' || c.relname::text || '(' || c.relkind::text || ')', ', '
                                 ORDER BY n.nspname, c.relname)
      INTO leftover
      FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname IN ('app_auth', 'app_clip', 'app_chat_collector', 'app_chat_detector')
       AND c.relkind IN ('r', 'p', 'v', 'm', 'S');
    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'schema move back: unlisted relations left in app schemas: %', leftover;
    END IF;
    SELECT pg_catalog.string_agg(p.oid::regprocedure::text, ', ' ORDER BY p.oid::regprocedure::text)
      INTO leftover
      FROM pg_catalog.pg_proc p
      JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname IN ('app_auth', 'app_clip', 'app_chat_collector', 'app_chat_detector');
    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'schema move back: unlisted functions left in app schemas: %', leftover;
    END IF;
END $do$;
