-- 업로드 정보에 카테고리와 예약 공개를 더한다(POK-381).
--
-- category_id: 유튜브 분류 번호(snippet.categoryId). 지금까지는 일꾼이 20(게임)을 박아 보냈다. 기존 줄은 그 값이다.
-- publish_at: 예약 공개 시각(status.publishAt). 유튜브는 비공개 영상에만 받는다: 그 시각에 비공개 → 공개로 바뀐다.
--             그 시각이 이미 지났으면 유튜브가 올리자마자 공개한다(다시 시도도 같다).

ALTER TABLE upload_requests
    ADD COLUMN category_id VARCHAR(4)  NOT NULL DEFAULT '20',
    ADD COLUMN publish_at  TIMESTAMPTZ,
    ADD CONSTRAINT ck_upload_requests_publish_at CHECK (publish_at IS NULL OR privacy_status = 'private');

ALTER TABLE clip_uploads
    ADD COLUMN category_id VARCHAR(4)  NOT NULL DEFAULT '20',
    ADD COLUMN publish_at  TIMESTAMPTZ,
    ADD CONSTRAINT ck_clip_uploads_publish_at CHECK (publish_at IS NULL OR privacy_status = 'private');
