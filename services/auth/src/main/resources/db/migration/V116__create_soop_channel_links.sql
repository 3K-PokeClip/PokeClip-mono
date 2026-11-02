-- SOOP 채널 연동 (POK-396). 회원 하나 ↔ SOOP 방송국 하나. 토큰은 SecretStore 참조만.
-- 치지직(V107)과 달리 닫히면 SOOP 아이디·닉네임을 바로 비운다(SOOP 개발자 약관 제11조 ⑰, ADR-097 결정 4).
-- 행 자체는 남긴다: 「SOOP 연동이 끊겼어요」를 그리려면 끊겼다는 사실과 사유가 필요하다.
CREATE TABLE soop_channel_links (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users (id),
    -- validate/live/status의 user_id. 로그에 찍지 않는다.
    soop_user_id      VARCHAR(64),
    -- user/stationinfo의 user_nick. 표시용.
    nickname          TEXT,
    scope             TEXT,
    access_token_ref  VARCHAR(255) NOT NULL,
    refresh_token_ref VARCHAR(255) NOT NULL,
    access_expires_at TIMESTAMPTZ  NOT NULL,
    last_refreshed_at TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ,
    -- USER_UNLINKED | REFRESH_REJECTED | WITHDRAWN
    revoke_reason     VARCHAR(32),
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_soop_links_alive_has_identity
        CHECK (revoked_at IS NOT NULL OR (soop_user_id IS NOT NULL AND nickname IS NOT NULL))
);
CREATE UNIQUE INDEX uq_soop_links_alive_soop_user ON soop_channel_links (soop_user_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX uq_soop_links_alive_user ON soop_channel_links (user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_soop_links_alive_expiry ON soop_channel_links (access_expires_at) WHERE revoked_at IS NULL;
CREATE INDEX idx_soop_links_user_created ON soop_channel_links (user_id, created_at);
