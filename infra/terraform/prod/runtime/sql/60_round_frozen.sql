-- 60 회차 불변 — stream_sessions.round_id 를 바꾸는 UPDATE 를 거부하는 트리거를 깐다(POK-525 운영 SQL 2).
--
-- 실행: psql -1 -v ON_ERROR_STOP=1 -f 60_round_frozen.sql (마스터 사용자). 00 ~ 50(운영 SQL 1)과 다른 때에 혼자 돌린다 —
-- 새 꼴 쓰기를 켜고 첫 새 꼴 행이 생긴 뒤(contract)다. 그 앞에 깔면 옛 판(A)으로 돌아간 구간의 늦은 4D 분리가 거부되고
-- 그 4D 는 유실된다(K5). 걸음은 런북(runbooks/runtime-1a.md)이 정한다. 다시 돌려도 같다(트리거는 이름이 없을 때만
-- 만든다 — 수렴한 재실행은 표를 잠그지 않는다). 적용 뒤 40_verify.sql 을 다시 돌려 VERIFY OK 를 본다.
--
-- 잠금 대기 상한: CREATE TRIGGER 는 stream_sessions 의 SHARE ROW EXCLUSIVE 를 잡아 그동안 세션 쓰기를 막는다. 열린 세션
-- 쓰기 뒤에서 무제한 기다리면 그 뒤 사이드카 세션 쓰기가 줄을 서므로, 이 파일이 SET LOCAL lock_timeout = '3s' 로 상한을
-- 직접 건다(런북 「M6 스키마 재실행」 의 lock_timeout=3s 와 같은 값 · psql -1 의 트랜잭션 안에서만 듣는다). 3초를 넘기면
-- 55P03 으로 멈추고 psql -1 이 파일 전체를 되돌린다 — 트리거는 깔리지 않는다. 다시 시도하는 때는 런북이 정한다.
--
-- 아래 두 표지 사이는 media/internal/index/ddl.go 의 roundFrozenDDL 과 바이트가 같아야 한다. media 의
-- prod_sql_contract_test.go 가 강제한다. 로컬 EnsureSchema 는 schemaDDL 뒤에 같은 글자를 돌린다. 마스터는 media_owner
-- 의 권한을 상속하지 않으므로(10 의 INHERIT FALSE) 표 소유자로 바꿔서 만든다.
--
-- 끝의 DO 블록이 깐 트리거의 모양을 단언한다 — 같은 이름의 트리거가 이미 있으면 위 블록은 건너뛰므로 그 트리거가
-- 제 모양인지 여기서 본다. 모두 통과하면 ROUND FROZEN OK 행이 나오고, 하나라도 틀리면
-- "round frozen failed: <식별자>, <식별자>" 로 실패한다.
--
-- 식별자:
--   round_frozen_trigger   stream_sessions 에 트리거 stream_sessions_round_frozen 이 있다(없으면 이것 하나만 실패한다)
--   round_frozen_enabled   일반 연결에서 켜져 있다('O' · 'A' — 'R' 은 replica 에서만 돈다)
--   round_frozen_timing    BEFORE UPDATE 행 단위다(tgtype 19 = 행 단위 1 + BEFORE 2 + UPDATE 16)
--   round_frozen_columns   대상 열이 round_id 하나다(UPDATE OF round_id) — 열이 없으면 모든 UPDATE 에서 돈다
--   round_frozen_when      WHEN (OLD.round_id IS DISTINCT FROM NEW.round_id) 가 있다 — 없으면 같은 값 UPDATE 도
--                          거부해 정당한 세션 UPDATE 가 막힌다(E27)
--   round_frozen_function  함수가 stream_sessions_reject_round_change() 이고 search_path 가 pg_catalog, pg_temp 로
--                          고정돼 있다
--
-- 40_verify.sql 은 이 트리거를 단언하지 않는다(운영 SQL 1 뒤에도 돌기 때문이다). 롤백 짝은 트리거 결함(정당한 세션
-- UPDATE 를 막음) 대응 전용이고 A 복귀 수단이 아니다:
--   DROP TRIGGER IF EXISTS stream_sessions_round_frozen ON stream_sessions;
--
-- 내장 함수 · 연산자는 40 과 같은 규칙으로 pg_catalog 에 맞춘다(search_path 가로채기 · 심층 방어, media/README.md).
-- 트랜잭션 문을 쓰지 않는다 — 원자성은 psql -1 이 준다.
\set ON_ERROR_STOP on

SET ROLE media_owner;
SET LOCAL lock_timeout = '3s';
-- BEGIN roundFrozenDDL
-- 회차 불변 — stream_sessions.round_id 를 다른 값으로 바꾸는 UPDATE 를 거부한다(23514 · NULL 에서 값 · 값에서 NULL 도
-- 바꾸는 것이다). 같은 값을 다시 쓰거나 다른 열만 바꾸는 UPDATE 는 트리거가 돌지 않는다(UPDATE OF round_id + WHEN) —
-- 그래서 함수는 따지지 않고 거부한다. 하한 검사(seq_below_floor)와 같은 꼴로 search_path 를 고정한다.
CREATE OR REPLACE FUNCTION stream_sessions_reject_round_change() RETURNS trigger
    LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $fn$
BEGIN
    RAISE EXCEPTION 'stream_sessions.round_id is frozen'
        USING ERRCODE = 'check_violation',
              DETAIL = pg_catalog.format('session=%s old=%s new=%s', OLD.session_id, OLD.round_id, NEW.round_id);
END $fn$;

-- 이름이 없을 때만 만든다 — 수렴한 재실행은 stream_sessions 를 잠그지 않는다(CREATE TRIGGER 는 SHARE ROW EXCLUSIVE 로
-- 그 표의 쓰기를 잠깐 막는다). 같은 이름의 다른 모양 트리거가 이미 있으면 건너뛰므로, 운영 SQL 2 는 끝에서 모양을 단언한다.
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = 'stream_sessions'::regclass::oid
                     AND tgname  = 'stream_sessions_round_frozen') THEN
        CREATE TRIGGER stream_sessions_round_frozen
            BEFORE UPDATE OF round_id ON stream_sessions
            FOR EACH ROW
            WHEN (OLD.round_id IS DISTINCT FROM NEW.round_id)
            EXECUTE FUNCTION stream_sessions_reject_round_change();
    END IF;
END $do$;
-- END roundFrozenDDL

DO $do$
DECLARE
    failed text[];
BEGIN
    WITH
    tg AS (
        SELECT t.oid, t.tgrelid, t.tgenabled, t.tgtype, t.tgattr, t.tgfoid
          FROM pg_catalog.pg_trigger t
         WHERE t.tgrelid = pg_catalog.to_regclass('public.stream_sessions')::oid
           AND t.tgname = 'stream_sessions_round_frozen'::name
    ),
    checks(id, ok) AS (
        SELECT 'round_frozen_trigger', EXISTS (SELECT 1 FROM tg)
        UNION ALL
        SELECT 'round_frozen_enabled', tg.tgenabled IN ('O'::"char", 'A'::"char") FROM tg
        UNION ALL
        SELECT 'round_frozen_timing', tg.tgtype = 19::int2 FROM tg
        UNION ALL
        -- tgattr 는 int2vector(첨자 0 부터)라 배열로 바로 비교하지 않고 열 이름으로 편다.
        SELECT 'round_frozen_columns',
               ARRAY(SELECT a.attname::text
                       FROM pg_catalog.unnest(tg.tgattr::pg_catalog.int2[]) AS k(n)
                       JOIN pg_catalog.pg_attribute a ON a.attrelid = tg.tgrelid AND a.attnum = k.n
                      ORDER BY 1) OPERATOR(pg_catalog.=) ARRAY['round_id']
          FROM tg
        UNION ALL
        SELECT 'round_frozen_when',
               pg_catalog.strpos(pg_catalog.pg_get_triggerdef(tg.oid),
                                 ' WHEN ((old.round_id IS DISTINCT FROM new.round_id)) ') > 0
          FROM tg
        UNION ALL
        SELECT 'round_frozen_function',
               EXISTS (SELECT 1 FROM pg_catalog.pg_proc p
                        WHERE p.oid = tg.tgfoid
                          AND p.oid = pg_catalog.to_regprocedure('public.stream_sessions_reject_round_change()')::oid
                          AND p.proconfig OPERATOR(pg_catalog.=) ARRAY['search_path=pg_catalog, pg_temp'])
          FROM tg
    )
    SELECT coalesce(pg_catalog.array_agg(id ORDER BY id), '{}') INTO failed FROM checks WHERE ok IS NOT TRUE;

    IF pg_catalog.cardinality(failed) > 0 THEN
        RAISE EXCEPTION 'round frozen failed: %', pg_catalog.array_to_string(failed, ', ');
    END IF;
END $do$;

SELECT 'ROUND FROZEN OK' AS round_frozen;

RESET ROLE;
