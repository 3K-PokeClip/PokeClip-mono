-- 유튜브 데이터 보관을 처리방침대로(POK-423, 처리방침 13절 · 유튜브 API 정책 III.D.2 · III.E.4).
--
-- 채널 번호 · 이름: 연동 중에는 30일마다 다시 받아 갱신하고, 해제하면 7일 뒤, 구글 쪽 철회 · 갱신 불가면 30일 뒤 닫힌 줄에서 지운다.
-- 그때 그 회원의 살아 있는 연동이 같은 채널이 아니면 clip에 그 채널의 영상 번호를 지우라고 알린다(아래 장부).
-- 같은 채널로 기한 안에 다시 연동했으면 영상 번호는 남는다(살아 있는 연동이 그 채널이다).

-- 마지막으로 channels.list로 채널 이름을 다시 받은 시각. NULL이면 아직 안 받았다(연동 시각부터 30일을 센다).
ALTER TABLE youtube_channel_links ADD COLUMN channel_checked_at TIMESTAMPTZ;

-- 닫힌 줄 중 채널이 아직 남은 것(지울 차례를 기다리는 것)만 훑는다.
CREATE INDEX idx_youtube_links_closed_channel ON youtube_channel_links (revoked_at)
    WHERE revoked_at IS NOT NULL AND channel_id IS NOT NULL;

-- clip에 「이 회원의 이 채널 영상 번호를 지워라」를 받을 때까지 보내는 장부. 탈퇴 장부(withdrawal_purge_jobs)와 따로 둔다:
-- 그 장부는 보낸 줄을 남기는데, 여기 채널 번호는 지우려고 보내는 개인정보라 보내면 줄째 지운다.
-- created_at이 clip이 지우는 범위의 끝이다: 그 뒤에 주문한 업로드는 남긴다(그 사이 같은 채널로 다시 연동했을 수 있다).
CREATE TABLE youtube_video_forget_jobs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel_id       VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL
);

-- 같은 채널을 두 번 적지 않는다(닫힌 줄이 여럿이어도 한 번 보내면 된다).
CREATE UNIQUE INDEX uq_youtube_video_forget_jobs ON youtube_video_forget_jobs (user_id, channel_id);
CREATE INDEX idx_youtube_video_forget_jobs_due ON youtube_video_forget_jobs (next_attempt_at);

COMMENT ON TABLE youtube_video_forget_jobs IS 'clip에 유튜브 영상 번호 지우기를 알리는 장부. 보내면 줄째 지운다 (POK-423)';
COMMENT ON COLUMN youtube_channel_links.channel_checked_at IS '마지막 channels.list 갱신 시각. NULL이면 created_at부터 30일 (POK-423)';
