-- 판별 서버가 만드는 첫 표다. 번호 대역은 V4xx — V1xx auth · V2xx clip · V3xx chat-collector.
-- 장부(이력 테이블)도 flyway_schema_history_chat_detector로 갈라 둔다. 대역과 장부를 모두
-- 나누는 이유는 POK-120 카드의 완료 조건이다.
--
-- 이 표는 판별 서버만 쓴다. chat_messages와 달리 공동 소유가 아니다.
CREATE TABLE chat_metrics (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- clip의 broadcasts.stream_id·chat_messages.stream_id와 같은 폭이어야 한다.
    -- 좁으면 22001(string data right truncation)로 거절당한다.
    stream_id       VARCHAR(128) NOT NULL,
    -- 이 줄이 몇 초짜리 창인가. 창 셋(3·5·10초)을 한 표에 담는다.
    window_size_ms  INTEGER      NOT NULL,
    -- 눈금 위치. epoch ms를 창 크기로 내림한 값이라 같은 창은 항상 같은 수가 된다.
    -- 이것이 clip의 중복 방어(UNIQUE(stream_id, source, window_start_ms))가 저절로
    -- 작동하는 근거다 — 창을 밀며 보면 이 값이 매번 달라져 방어가 통째로 뚫린다.
    window_start_ms BIGINT       NOT NULL,
    message_count   INTEGER      NOT NULL,
    -- 지금은 판정에 안 쓴다. 기능명세 C7(M5)의 A/B에 쓸 재료를 지금부터 쌓는다
    -- (연구노트 미결 5번: "메시지 수 대비 어느 정도 개선되는가").
    chatter_count   INTEGER      NOT NULL,
    -- 카드를 보내려고 집은 시각. 발행 창(5000) 줄에만 의미가 있다.
    --
    -- NULL은 「한 번도 안 집혔다」와 「집었다가 놓았다」 둘 다다. 실패는 원칙적으로 되돌리지
    -- 않지만 — 늦게 도착한 카드는 편집자 화면을 과거로 오염시키므로(PRD 결정) —
    -- 조각이 아직 장부에 안 온 경우(수집 서버 계약의 not_yet_indexed)만 예외다.
    -- 그것은 몇 초 뒤면 답이 바뀌는데, 잡은 채로 두면 그 창은 영영 다시 안 집히고
    -- 채팅에는 백필이 없어 되찾을 방법도 없다. 되돌린 창도 판정이 되돌아보는 폭을
    -- 벗어나면 목록에서 빠지므로 무한 재시도가 아니다.
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 같은 창을 두 줄로 만들지 않는다. 한 바퀴가 밀려 겹쳐 돌아도 여기서 접힌다.
    CONSTRAINT uq_chat_metrics_window UNIQUE (stream_id, window_size_ms, window_start_ms)
);

-- "이 방송, 이 창 크기의 최근 W분치를 최신순으로" — 베이스라인 조회가 이 모양이다.
CREATE INDEX idx_chat_metrics_baseline
    ON chat_metrics (stream_id, window_size_ms, window_start_ms DESC);

-- 치우기용. PK가 id라 보관 기간 조회가 전체 훑기가 된다.
CREATE INDEX idx_chat_metrics_created ON chat_metrics (created_at);
