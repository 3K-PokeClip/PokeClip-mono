-- 「영상 만들기 = 렌더 + 유튜브 바로 올리기」(POK-291).
--
-- 1. upload_requests: 「렌더가 끝나면 이 정보로 올려 줘」라는 의도. 편집본 판 하나에 한 줄이다.
--    영상(clips) 줄이 아니라 편집본 판에 묶는 이유: 렌더가 실패해 같은 판을 다시 주문하면 새 영상 줄이 생기는데,
--    의도가 판에 있어야 그 새 영상이 성공할 때 저장된 정보로 이어서 올린다.
--    같은 판에 다시 누르면 덮어쓴다(마지막 누름이 이긴다).
-- 2. clip_uploads에 칸을 더한다: 태그 · 공개 범위 · 아동용 · 썸네일(무엇을 붙이나 + 붙었나). 기존 줄은 기본값이다.

CREATE TABLE upload_requests (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 같은 서버(clip)의 표라 FK를 건다. ON DELETE가 없어 탈퇴 정리가 recipes보다 먼저 지운다.
    recipe_id               BIGINT        NOT NULL REFERENCES recipes (id),
    recipe_version          INTEGER       NOT NULL,
    -- 마지막으로 누른 사람(JWT sub). 자동으로 만든 업로드 줄의 requested_by가 된다.
    requested_by            VARCHAR(128)  NOT NULL,
    -- 유튜브 규칙은 clip_uploads와 같다(제목 1~100자, 설명 5000바이트, 꺾쇠 금지). 검사는 주문 문이 한다.
    title                   VARCHAR(100)  NOT NULL,
    description             VARCHAR(5000) NOT NULL DEFAULT '',
    -- 태그 목록(문자열 배열). clip_uploads.tags와 같은 타입이다. 칸 하나에 통째로 둔다: 질의하지 않고 주문서로 옮기기만 한다.
    tags                    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    privacy_status          VARCHAR(8)    NOT NULL DEFAULT 'private',
    made_for_kids           BOOLEAN       NOT NULL DEFAULT false,
    -- 썸네일: none(유튜브가 고름) · scene(완성 영상의 한 장면, 업로드 일꾼이 뽑는다) · file(사용자가 올린 그림)
    thumbnail_source        VARCHAR(8)    NOT NULL DEFAULT 'none',
    -- scene일 때만. 완성 영상 첫 장면 기준 ms.
    thumbnail_offset_ms     BIGINT,
    -- file일 때만. CLIPS_BUCKET의 upload-thumbnails/{streamerUserId}/{uuid}.{jpg|png}
    thumbnail_s3_key        VARCHAR(512),
    thumbnail_content_type  VARCHAR(32),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_upload_requests_recipe_version UNIQUE (recipe_id, recipe_version),
    CONSTRAINT ck_upload_requests_tags CHECK (jsonb_typeof(tags) = 'array'),
    CONSTRAINT ck_upload_requests_privacy CHECK (privacy_status IN ('private', 'unlisted', 'public')),
    CONSTRAINT ck_upload_requests_thumbnail_source CHECK (thumbnail_source IN ('none', 'scene', 'file')),
    CONSTRAINT ck_upload_requests_thumbnail_scene CHECK ((thumbnail_source = 'scene') = (thumbnail_offset_ms IS NOT NULL)),
    CONSTRAINT ck_upload_requests_thumbnail_file CHECK ((thumbnail_source = 'file')
        = (thumbnail_s3_key IS NOT NULL AND thumbnail_content_type IS NOT NULL))
);

ALTER TABLE clip_uploads
    ADD COLUMN tags                   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN privacy_status         VARCHAR(8)   NOT NULL DEFAULT 'private',
    ADD COLUMN made_for_kids          BOOLEAN      NOT NULL DEFAULT false,
    ADD COLUMN thumbnail_source       VARCHAR(8)   NOT NULL DEFAULT 'none',
    ADD COLUMN thumbnail_offset_ms    BIGINT,
    ADD COLUMN thumbnail_s3_key       VARCHAR(512),
    ADD COLUMN thumbnail_content_type VARCHAR(32),
    -- 썸네일이 붙었나. none(붙일 것 없음) → pending(붙일 것 있음, 결과 전) → set | failed.
    -- 영상 상태(status)와 따로 둔다: 「영상은 올라갔고 썸네일만 못 붙였다」가 있다(ck_clip_uploads_video는 영상만 본다).
    ADD COLUMN thumbnail_status       VARCHAR(8)   NOT NULL DEFAULT 'none',
    ADD COLUMN thumbnail_error_code   VARCHAR(32),
    ADD CONSTRAINT ck_clip_uploads_tags CHECK (jsonb_typeof(tags) = 'array'),
    ADD CONSTRAINT ck_clip_uploads_privacy CHECK (privacy_status IN ('private', 'unlisted', 'public')),
    ADD CONSTRAINT ck_clip_uploads_thumbnail_source CHECK (thumbnail_source IN ('none', 'scene', 'file')),
    ADD CONSTRAINT ck_clip_uploads_thumbnail_status CHECK (thumbnail_status IN ('none', 'pending', 'set', 'failed'));
