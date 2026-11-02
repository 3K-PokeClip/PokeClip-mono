-- 점프카드 한 장 = 한 줄. 판별기(2A)·핫키(4B, POK-119)가 넣고 웹이 실시간으로 받는다.
CREATE SEQUENCE jump_card_event_seq;

CREATE TABLE jump_cards (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 같은 서버(clip)의 표라 FK를 건다. 없는 방송엔 카드를 못 넣는다. auth 표는 참조하지 않는다(ADR-022).
    stream_id           VARCHAR(128) NOT NULL REFERENCES broadcasts (stream_id),
    -- NOT NULL · 기본값 없음. UNIQUE에 NULL이 끼면 중복 방어가 통째로 뚫린다.
    source              VARCHAR(16)  NOT NULL,
    -- 추적용이다. 판별기 로그와 이 표를 잇는 값. UNIQUE가 아니고(중복 방어는 아래 제약이 한다) 핫키는 비운다.
    event_id            VARCHAR(128),
    -- 방송 시작=0 기준 ms. 절대 시각이면 서버 간 시계 오차가 자연키에 들어간다. 반올림하지 않는다.
    stream_timestamp_ms BIGINT       NOT NULL,
    window_start_ms     BIGINT       NOT NULL,
    window_end_ms       BIGINT       NOT NULL,
    score               INTEGER,
    -- 판정 근거(배수·건수 등). 모양이 멘토 협업 미결이라 칸을 쪼개지 않는다.
    evidence            JSONB,
    -- 누가 집었나. FK 없음 — auth users 표를 참조하지 않는다(streamer_id와 같은 처리).
    claimed_by          VARCHAR(128),
    claimed_at          TIMESTAMPTZ,
    hidden_at           TIMESTAMPTZ,
    hidden_by           VARCHAR(128),
    -- 이 카드가 마지막으로 바뀐 순번. 정렬과 "언제 바뀌었나"에 쓴다.
    -- 따라잡기 조건(seq > last)으로 단독 사용하지 않는다 — 시퀀스는 트랜잭션 밖에서 증가해
    -- 번호 순서와 커밋 순서가 다를 수 있고, 그러면 카드가 조용히 빠진다(PRD 결정).
    event_seq           BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 중복 방어는 이 하나뿐이다. ON CONFLICT는 제약 하나만 지정할 수 있어 둘을 걸면
    -- 한쪽이 예외로 튀고 "중복"과 "저장 실패"가 같은 예외가 된다(POK-82 함정).
    CONSTRAINT uq_jump_cards_window UNIQUE (stream_id, source, window_start_ms),
    CONSTRAINT ck_jump_cards_source CHECK (source IN ('auto', 'hotkey')),
    -- API의 400은 그 경로만 막는다. 나중에 다른 경로(핫키·관리 도구)가 생겨도 여기서 걸린다.
    CONSTRAINT ck_jump_cards_window CHECK (window_start_ms < window_end_ms),
    CONSTRAINT ck_jump_cards_ts_in_window CHECK (window_start_ms <= stream_timestamp_ms AND stream_timestamp_ms <= window_end_ms)
);

-- 연결 직후 스냅샷 "이 방송의 카드 전부를 순번 순으로".
CREATE INDEX idx_jump_cards_stream_seq ON jump_cards (stream_id, event_seq);

-- ON CONFLICT가 아무것도 안 넣어도 이 시퀀스는 소비된다(실측: seq 1 → INSERT 0 0 → seq 2).
-- event_seq에 구멍이 생기지만 무해하다 — 따라잡기가 전체 스냅샷이라 번호의 연속성에 기대지 않는다.
--
-- 왜 트리거인가: 카드를 쓰는 문이 다섯(2A·claim·unclaim·hide·unhide)이라 문마다 nextval을 쓰면
-- 한 군데 빠뜨리기 쉽고, 빠진 변경은 순번이 안 올라 스냅샷 정렬에서 뒤로 밀린다.
-- updated_at도 여기서 DB 시계로 채운다 — 앱 시계(@UpdateTimestamp)는 서버마다 다르다.
-- 숨은 동작이므로 이 주석이 없으면 코드만 봐서는 보이지 않는다.
CREATE FUNCTION jump_cards_touch() RETURNS trigger AS $$
BEGIN
    NEW.event_seq  := nextval('jump_card_event_seq');
    NEW.updated_at := now();
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_jump_cards_touch
    BEFORE INSERT OR UPDATE ON jump_cards
    FOR EACH ROW EXECUTE FUNCTION jump_cards_touch();
