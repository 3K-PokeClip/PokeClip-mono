-- 20 스키마 — media_owner 로 바꿔 stream_* 표 · 함수를 만든다(소유자 = media_owner).
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 20_schema.sql (마스터 사용자 · 이 파일이 있는 디렉터리의
-- stream_schema.sql 을 \ir 로 읽는다). 사본은 IF NOT EXISTS · 카탈로그 확인 DO 블록이라 다시 돌려도
-- 같은 상태로 수렴한다.
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

SET ROLE media_owner;
-- 다시 돌릴 때(한 창 절차) 두 표를 사이드카 insertOnce 와 같은 순서(sessions → segments)로 먼저 다 잡는다. 사본은
-- segments 를 먼저 잡고(CREATE INDEX IF NOT EXISTS 는 이름 확인 전에 SHARE) sessions 를 나중에 잡아, 그 사이에
-- 사이드카 트랜잭션이 끼면 교착한다. 처음 적용에는 표가 없어 건너뛴다. 잠금은 psql -1 트랜잭션 끝까지 간다.
DO $do$
BEGIN
    IF pg_catalog.to_regclass('public.stream_sessions') IS NOT NULL THEN
        LOCK TABLE public.stream_sessions IN ACCESS EXCLUSIVE MODE;
    END IF;
    IF pg_catalog.to_regclass('public.stream_segments') IS NOT NULL THEN
        LOCK TABLE public.stream_segments IN SHARE ROW EXCLUSIVE MODE;
    END IF;
END $do$;
\ir stream_schema.sql
RESET ROLE;
