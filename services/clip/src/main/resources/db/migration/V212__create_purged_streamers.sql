-- 탈퇴한 스트리머 명부(POK-256). auth가 탈퇴를 알리면 한 줄이 생기고 영영 남는다(회원 번호는 재사용되지 않는다).
--
-- 두 가지 일을 한다.
--  1. 늦게 도착한 방송 시작·종료 편지가 지운 방송을 되살리지 못하게 막는다(편지 처리기가 이 표를 본다).
--  2. 지우기를 끝까지 이어 간다. 파일 지우기가 실패해도 지울 주소가 여기 남아 정리기가 다시 한다.
--     표의 줄은 먼저 지워지므로, 주소를 이 표에 옮겨 두지 않으면 재시도할 때 무엇을 지울지 모른다.
CREATE TABLE purged_streamers (
    streamer_id      VARCHAR(128) PRIMARY KEY,
    requested_at     TIMESTAMPTZ  NOT NULL,
    -- 완성 영상 창고(CLIPS_BUCKET)에서 아직 못 지운 접두사. clips/{clipId}/ · thumbnails/{kind}/{id}.jpg
    pending_prefixes TEXT[]       NOT NULL DEFAULT '{}',
    -- 녹화 조각 장부(1번 stream_segments)의 열쇠. 줄과 파일을 다 지울 때까지 정리기가 이 열쇠로 다시 찾는다.
    segment_keys     TEXT[]       NOT NULL DEFAULT '{}',
    completed_at     TIMESTAMPTZ
);

-- 정리기가 「아직 안 끝난 줄」만 훑는다.
CREATE INDEX idx_purged_streamers_pending ON purged_streamers (requested_at) WHERE completed_at IS NULL;
