-- 유튜브 영상 번호를 처리방침대로 다룬다(POK-423).
--
-- 처리방침 13절: 올린 영상 번호는 연동 중 30일마다 아직 있는지 확인하고(지워졌으면 번호를 지운다), 연동을 해제하면 7일 안에,
-- 구글 쪽 철회 · 갱신 불가면 30일 안에 지운다. 언제 지울지는 auth가 정하고(연동 기록이 auth에 있다) 이 표는 그 말을 따른다.
--
-- 🔴 번호를 지운 줄은 「올림」 그대로 둔다. 상태를 바꾸면 같은 영상을 다시 올릴 자리가 열려 채널에 같은 영상이 둘 생길 수 있다
-- (자리 색인 uq_clip_uploads_active). 화면은 영상 번호가 없는 올림을 링크 없이 보여 준다.

ALTER TABLE clip_uploads
    -- 일꾼이 올린 채널(resolve가 준 채널). 해제 · 다른 채널로 재연동하면 이 채널의 번호만 골라 지운다. 사람이 푼 줄 · 이 칸이 생기기
    -- 전 줄은 NULL이다(어느 채널인지 모른다: auth가 「그 회원이 지금 그 채널에 연동돼 있지 않다」고 하면 같이 지운다).
    ADD COLUMN youtube_channel_id VARCHAR(64),
    -- 마지막으로 유튜브에 「아직 있나」를 확인한 시각. NULL이면 아직 안 했다(올린 시각부터 30일을 센다).
    ADD COLUMN video_checked_at TIMESTAMPTZ,
    -- 영상 번호를 지운 시각. 지운 뒤에는 번호도 채널도 없다.
    ADD COLUMN video_forgotten_at TIMESTAMPTZ,
    DROP CONSTRAINT ck_clip_uploads_video,
    ADD CONSTRAINT ck_clip_uploads_video
        CHECK ((youtube_video_id IS NOT NULL) = (status = 'uploaded' AND video_forgotten_at IS NULL)),
    ADD CONSTRAINT ck_clip_uploads_forgotten
        CHECK (video_forgotten_at IS NULL OR (status = 'uploaded' AND youtube_channel_id IS NULL));

-- 지우기(주인별) · 30일 확인(오래 안 본 것부터)이 영상 번호가 있는 줄만 본다.
CREATE INDEX idx_clip_uploads_owner_video ON clip_uploads (channel_owner) WHERE youtube_video_id IS NOT NULL;
CREATE INDEX idx_clip_uploads_video_check ON clip_uploads ((COALESCE(video_checked_at, created_at)))
    WHERE youtube_video_id IS NOT NULL;
