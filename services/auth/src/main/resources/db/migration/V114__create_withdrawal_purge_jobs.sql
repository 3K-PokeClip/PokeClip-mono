-- 탈퇴 뒤 다른 서버에 「이 회원 기록을 지워라」를 전하는 장부(POK-256).
-- 탈퇴 트랜잭션 안에서 줄을 적고, 발송기가 받을 때까지 다시 보낸다. 커밋 뒤 정리 스레드에 맡기면
-- 한 번 실패한 알림이 영영 사라진다(재시도가 없다). 장부에 적어 두면 서버가 재시작해도 이어진다.
--
-- target: CLIP(그 회원의 방송·카드·편집본·영상) · COLLECTOR(그 회원이 연동했던 치지직 채널의 채팅·후원·방송 정보)
-- channel_id는 COLLECTOR만 채운다. 사람을 잇는 값이라 로그에 안 찍는다(치지직 연동과 같은 규칙).
CREATE TABLE withdrawal_purge_jobs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- CASCADE는 audio_track_labels와 같은 이유다. 운영은 회원 줄을 안 지우고 익명화하므로 이 줄이 딸려 가는 일이 없고,
    -- 시험이 회원을 통째로 지울 때 장부가 그것을 막지 않는다.
    user_id          BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target           VARCHAR(16)  NOT NULL,
    channel_id       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    done_at          TIMESTAMPTZ,
    CONSTRAINT ck_withdrawal_purge_target CHECK (target IN ('CLIP', 'COLLECTOR')),
    CONSTRAINT ck_withdrawal_purge_channel CHECK ((target = 'COLLECTOR') = (channel_id IS NOT NULL))
);

-- 같은 탈퇴를 두 번 적지 않는다(탈퇴는 멱등이고, 이미 탈퇴한 회원은 본체가 일찍 돌아간다).
CREATE UNIQUE INDEX uq_withdrawal_purge_jobs
    ON withdrawal_purge_jobs (user_id, target, COALESCE(channel_id, ''));

-- 발송기가 「아직 안 보낸 것 중 때가 된 것」만 훑는다.
CREATE INDEX idx_withdrawal_purge_jobs_due
    ON withdrawal_purge_jobs (next_attempt_at) WHERE done_at IS NULL;
