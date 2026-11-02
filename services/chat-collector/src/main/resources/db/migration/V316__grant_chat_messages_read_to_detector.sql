-- 판별 서버 DB 계정(app_chat_detector)에 채팅 원문 읽기만 준다(POK-407). 운영은 서버마다 DB 계정이 따로고,
-- chat_messages는 이 서버가 V301로 만들어 이 서버 계정이 주인이다. 주인이 주는 것이 맞아서 여기 둔다.
--
-- 계정이 없으면 건너뛴다. 로컬 compose · CI는 소유자 계정 하나로 붙어 그 계정이 없다.
-- 🔴 운영에서 판별 서버 계정이 이 마이그레이션 뒤에 생기면 이 줄은 다시 안 돈다. 1번이 계정 넷을 서버보다 먼저
-- 만들고, 늦었으면 services/README.md 「서버별 DB 계정과 권한」의 같은 줄을 손으로 돌린다.
--
-- 「이 서버가 앞으로 만들 표 전부 읽기」(ALTER DEFAULT PRIVILEGES)로 주지 않는다. 그러면 수집 키 표(chat_ingest_keys)까지
-- 판별 서버에 열린다. 판별 서버가 읽는 것은 이 표 하나다(ChatWindowReader).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_chat_detector') THEN
        GRANT SELECT ON chat_messages TO app_chat_detector;
    END IF;
END
$$;
