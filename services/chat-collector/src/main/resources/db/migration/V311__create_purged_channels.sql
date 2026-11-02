-- 탈퇴한 스트리머의 치지직 채널(POK-256). auth가 탈퇴를 알리면 한 줄이 생긴다.
-- 정리기가 이 채널의 채팅·후원·방송 정보와 채팅 원본 파일을 지운다.
--
-- 🔴 영원히 막지 않는다. 같은 채널을 나중에 다른 계정이 연동할 수 있다(같은 사람이 새 구글 계정으로).
-- 그래서 지우는 범위를 「탈퇴 알림 + late_window(기본 10분) 전에 받은 것」으로 자르고, 그 창이 닫히면 줄을 닫는다.
-- 창 안에 늦게 적재된 채팅(바구니에 남아 있던 것)까지 잡으려는 것이 창의 이유다.
CREATE TABLE purged_channels (
    channel_id    TEXT        PRIMARY KEY,
    requested_at  TIMESTAMPTZ NOT NULL,
    completed_at  TIMESTAMPTZ
);

CREATE INDEX idx_purged_channels_pending ON purged_channels (requested_at) WHERE completed_at IS NULL;
