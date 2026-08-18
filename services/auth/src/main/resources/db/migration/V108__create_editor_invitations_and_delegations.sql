-- 편집자 초대와 위임 (POK-57). 스트리머가 이메일로 초대 → 편집자가 수락 → 위임 생성.
-- 이메일로 계정을 정확히 하나 찾는 것이 전제다. 소문자로 통일한 뒤 제약을 건다 —
-- 구글은 소문자로 주지만 스트리머는 초대창에 대문자를 섞어 칠 수 있다.
UPDATE users SET email = lower(email) WHERE email <> lower(email);
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);

CREATE TABLE editor_invitations (
    id           BIGSERIAL   PRIMARY KEY,
    streamer_id  BIGINT      NOT NULL REFERENCES users (id),
    invitee_id   BIGINT      NOT NULL REFERENCES users (id),
    -- PENDING | ACCEPTED | DECLINED | CANCELED. 만료는 상태가 아니라 expires_at으로 판정한다 —
    -- 상태로 두면 만료 시각에 값을 바꿔주는 배치가 필요하다(chzzk_channel_links와 같은 원칙).
    status       VARCHAR(16) NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    -- 수락·거절·취소한 시각. PENDING인 동안은 비어 있다.
    responded_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    -- 자동완성 오입력을 DB가 막는다. 앱에서도 먼저 거르지만 최후 방어선이 여기다.
    CONSTRAINT ck_invitations_not_self CHECK (streamer_id <> invitee_id)
);

-- 살아있는 초대는 한 쌍당 하나. 재초대는 이 행의 expires_at을 미는 것이지 새 행이 아니다.
-- 거절·취소된 이력은 status가 PENDING이 아니라 이 인덱스에 안 걸리고 그대로 쌓인다.
CREATE UNIQUE INDEX uq_invitations_pending_pair
    ON editor_invitations (streamer_id, invitee_id) WHERE status = 'PENDING';
-- 초대함(받은 목록)은 응답 가능한 것만 본다.
CREATE INDEX idx_invitations_invitee_pending
    ON editor_invitations (invitee_id) WHERE status = 'PENDING';
-- 보낸 목록은 처리된 이력까지 최신순으로 본다 — 위 부분 인덱스로는 못 탄다.
CREATE INDEX idx_invitations_streamer_created
    ON editor_invitations (streamer_id, created_at);

CREATE TABLE editor_delegations (
    id            BIGSERIAL   PRIMARY KEY,
    streamer_id   BIGINT      NOT NULL REFERENCES users (id),
    editor_id     BIGINT      NOT NULL REFERENCES users (id),
    -- 어느 초대에서 왔는지. 초대 없이 생기는 위임은 없다.
    invitation_id BIGINT      NOT NULL REFERENCES editor_invitations (id),
    granted_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    -- STREAMER=내보냄 | EDITOR=나감. 행을 지우지 않는 이유가 이 구분이다 —
    -- 내보낸 것과 나간 것은 다른 사건이다.
    revoked_by    VARCHAR(16)
);

-- 살아있는 위임은 한 쌍당 하나. 해제 후 재초대·재수락하면 새 행이 쌓인다.
CREATE UNIQUE INDEX uq_delegations_alive_pair
    ON editor_delegations (streamer_id, editor_id) WHERE revoked_at IS NULL;
-- 내 편집자들 / 내가 맡은 스트리머들. 둘 다 살아있는 것만 본다.
CREATE INDEX idx_delegations_alive_streamer
    ON editor_delegations (streamer_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_delegations_alive_editor
    ON editor_delegations (editor_id) WHERE revoked_at IS NULL;

COMMENT ON TABLE editor_invitations IS '편집자 초대. 만료는 expires_at으로 파생하고 상태 컬럼에 쓰지 않는다 (POK-57)';
COMMENT ON TABLE editor_delegations IS '편집자 위임. 행을 지우지 않고 revoked_at·revoked_by로 닫는다 (POK-57)';
COMMENT ON COLUMN editor_delegations.revoked_by IS 'STREAMER=스트리머가 내보냄, EDITOR=편집자가 나감';
