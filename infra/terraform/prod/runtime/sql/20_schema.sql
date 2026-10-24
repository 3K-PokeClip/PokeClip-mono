-- 20 스키마 — media_owner 로 바꿔 stream_* 표 · 함수를 만든다(소유자 = media_owner).
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 20_schema.sql (마스터 사용자 · 이 파일이 있는 디렉터리의
-- stream_schema.sql 을 \ir 로 읽는다). 사본은 IF NOT EXISTS · 카탈로그 확인 DO 블록이라 다시 돌려도
-- 같은 상태로 수렴한다.
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

SET ROLE media_owner;
\ir stream_schema.sql
RESET ROLE;
