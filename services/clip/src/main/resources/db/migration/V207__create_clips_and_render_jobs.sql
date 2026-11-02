-- 영상 만들기 주문(POK-125). 표 셋 — 완성 영상(clips) · 주문 기록(render_jobs) · 받은 보고 장부(render_job_events).
-- 편집 기록(recipes)과 다른 표다: 그쪽은 사람이 고치고 이쪽은 서버(일꾼의 보고)가 고친다(카드 결정).

-- 완성 영상 한 벌 = 한 줄. 「어느 편집본 몇 판으로 만든 것인가」와 상태·결과가 산다.
CREATE TABLE clips (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stream_id       VARCHAR(128) NOT NULL REFERENCES broadcasts (stream_id),
    recipe_id       BIGINT       NOT NULL REFERENCES recipes (id),
    -- 주문 시점의 판. 레시피가 뒤에 고쳐져도 이 영상이 무엇으로 만들어졌는지는 여기 남는다.
    recipe_version  INTEGER      NOT NULL,
    -- 주문한 사람(JWT sub). FK 없음 — auth 표를 참조하지 않는다(ADR-022).
    requested_by    VARCHAR(128) NOT NULL,
    -- queued(주문됨) → rendering(만드는 중) → rendered(완성) | failed(실패). 업로드 상태는 POK-220이 더한다.
    status          VARCHAR(16)  NOT NULL,
    -- 완성했을 때 일꾼이 보고한 산출물 목록(계약1 4절 result) 그대로.
    outputs         JSONB,
    -- 실패했을 때 계약1 error.code · message. 성공이면 NULL.
    error_code      VARCHAR(32),
    error_message   VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_clips_status CHECK (status IN ('queued', 'rendering', 'rendered', 'failed'))
);

-- 계약1 2절 idempotencyKey — 「같은 편집본 같은 판의 진행 중 주문은 하나」. 더블클릭이 두 번째 주문을 못 만든다.
-- 종결(rendered·failed)되면 자리가 비어 다시 주문할 수 있다(재요청 = 새 줄). 부분 색인이 그 규칙 그대로다.
CREATE UNIQUE INDEX uq_clips_open_recipe ON clips (recipe_id, recipe_version)
    WHERE status IN ('queued', 'rendering');

-- 보관함·목록(POK-243)이 「이 방송의 영상들, 최근순」으로 읽는다.
CREATE INDEX idx_clips_stream ON clips (stream_id, id DESC);

-- 주문 기록. 완성 영상 하나에 주문 하나(1:1). 재요청은 새 영상 줄이다.
CREATE TABLE render_jobs (
    -- 계약1 jobId(UUIDv4). 일꾼이 보고할 때 이 값으로 부른다.
    id                UUID         PRIMARY KEY,
    clip_id           BIGINT       NOT NULL UNIQUE REFERENCES clips (id),
    -- queued → started → succeeded | failed. PROGRESS는 started를 유지한다(계약1 상태머신).
    status            VARCHAR(16)  NOT NULL,
    -- 큐에 실은 주문서 본문(계약1 봉투) 그대로. 발행이 실패하면 이 값으로 다시 보낸다(선기록·후발행 outbox).
    payload           JSONB        NOT NULL,
    -- 큐가 받았다고 답한 시각. NULL이면 아직 못 실은 것이고 재전송 대상이다.
    published_at      TIMESTAMPTZ,
    -- 지금 유효한 실행. 일꾼이 STARTED를 보낼 때마다 새로 발급하고, 옛 토큰의 보고는 409로 거절한다(계약1 fencing).
    execution_token   UUID,
    -- 발급한 토큰의 순번(1-시작). 3이면 마지막 시도다(계약1 attemptOrdinal).
    attempt_ordinal   INTEGER      NOT NULL DEFAULT 0,
    -- 같은 토큰 안에서 단조. 토큰이 바뀌면 0으로 돌아간다.
    progress_percent  INTEGER      NOT NULL DEFAULT 0,
    progress_stage    VARCHAR(64),
    last_event_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_render_jobs_status CHECK (status IN ('queued', 'started', 'succeeded', 'failed')),
    CONSTRAINT ck_render_jobs_attempt CHECK (attempt_ordinal >= 0),
    CONSTRAINT ck_render_jobs_percent CHECK (progress_percent BETWEEN 0 AND 100)
);

-- 못 실은 주문을 찾는 자리(outbox 재전송).
CREATE INDEX idx_render_jobs_unpublished ON render_jobs (created_at) WHERE published_at IS NULL;

-- 받은 보고 장부. (job, eventId) UNIQUE가 멱등의 진짜 방어선이다 — 같은 보고가 두 번 오면 저장한 응답을 그대로 되돌려준다.
-- 잡과 같은 수명이다(계약1 4절 「기수신 기록 보존 = 잡 레코드와 같은 수명」).
CREATE TABLE render_job_events (
    job_id           UUID         NOT NULL REFERENCES render_jobs (id),
    event_id         UUID         NOT NULL,
    event_type       VARCHAR(32)  NOT NULL,
    -- 그때 돌려준 상태 코드와 본문. 재전송에 같은 것을 준다.
    response_status  INTEGER      NOT NULL,
    response_body    JSONB        NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, event_id)
);
