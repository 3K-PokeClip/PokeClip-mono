-- 유튜브 업로드 주문(POK-220). 완성 영상(clips) 한 벌의 영상 파일 하나를 스트리머 채널에 올린다.
-- 🔴 핵심 불변식: 영상 바이트는 이 줄의 session_uri(유튜브 이어 올리기 주소)로만 보낸다. 주소가 하나라 영상도 많아야 하나다.
CREATE TABLE clip_uploads (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clip_id           BIGINT        NOT NULL REFERENCES clips (id),
    -- 계약6 outputId. 그 벌의 video 산출물을 올린다.
    output_id         VARCHAR(32)   NOT NULL,
    -- 주문한 사람(JWT sub). 편집자일 수 있다: 승인 게이트는 없다(2026-08-30 결정).
    requested_by      VARCHAR(128)  NOT NULL,
    -- 누구 채널에 올리나 = 방송의 스트리머(broadcasts.streamer_id). 주문한 사람이 아니다(ADR-010 Path A).
    channel_owner     VARCHAR(128)  NOT NULL,
    -- 유튜브 제목 규칙: 1~100자, <·> 금지. 설명은 5000바이트.
    title             VARCHAR(100)  NOT NULL,
    description       VARCHAR(5000) NOT NULL DEFAULT '',
    -- queued(주문됨) → uploading(올리는 중) → uploaded(올림) | failed(유튜브에 영상이 없는 것이 확실) | checking(결과 불명, 사람이 확인)
    status            VARCHAR(16)   NOT NULL,
    -- 일꾼에게 보낸 주문서 본문. 발행이 실패하면 이 값으로 다시 보낸다(outbox, 렌더와 같은 모양).
    payload           JSONB         NOT NULL,
    published_at      TIMESTAMPTZ,
    -- 유튜브가 준 이어 올리기 주소. 이 주소 자체가 올리기 권한이라 응답·로그에 내보내지 않는다. 한 번 적히면 안 바뀐다.
    session_uri       VARCHAR(2048),
    -- 일꾼이 이 주문을 잡은 횟수(1-시작). 진단용이다: 자동 재시도를 막는 것은 횟수가 아니라 session_uri다.
    attempt_ordinal   INTEGER       NOT NULL DEFAULT 0,
    youtube_video_id  VARCHAR(32),
    error_code        VARCHAR(32),
    error_message     VARCHAR(512),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_clip_uploads_status CHECK (status IN ('queued', 'uploading', 'checking', 'uploaded', 'failed')),
    CONSTRAINT ck_clip_uploads_video CHECK ((status = 'uploaded') = (youtube_video_id IS NOT NULL))
);

-- 「같은 영상 같은 벌은 채널에 하나」. 실패(영상이 없는 것이 확실)만 자리를 비운다: 올렸거나 결과 불명이면 다시 주문할 수 없다.
-- 더블클릭·두 번 배달의 두 번째는 이 색인에 걸려 첫 줄을 돌려받는다.
CREATE UNIQUE INDEX uq_clip_uploads_active ON clip_uploads (clip_id, output_id) WHERE status <> 'failed';

-- 영상 조회·보관함이 「이 영상의 가장 최근 업로드」를 읽는다.
CREATE INDEX idx_clip_uploads_clip ON clip_uploads (clip_id, id DESC);

-- 못 실은 주문(outbox 재전송).
CREATE INDEX idx_clip_uploads_unpublished ON clip_uploads (created_at) WHERE published_at IS NULL;
