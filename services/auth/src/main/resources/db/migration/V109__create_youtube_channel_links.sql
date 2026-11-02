-- 유튜브 채널 연동 (POK-121, 계약5). 회원 하나 ↔ 살아있는 연동 하나.
CREATE TABLE youtube_channel_links (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users (id),
    -- channels.list가 준 값. 동의 시점에 확정되고 그 뒤로 UPDATE되지 않는다 — 구글은 고른 채널 하나만
    -- 그 토큰에 묶어 주므로(2026-08-24 실측) 바꾸는 수단은 재연동(새 행)뿐이다.
    -- 사용자 입력을 그대로 저장하지 않는다(목록 대조 후 저장). 로그에도 찍지 않는다.
    channel_id        VARCHAR(64)  NOT NULL,
    channel_name      TEXT         NOT NULL,
    -- 동의 화면에서 체크박스를 지울 수 있어 실제로 받은 범위를 남긴다. 갱신 응답엔 없을 수 있다.
    scope             TEXT,
    -- SecretStore 참조. access·refresh 원문은 여기 없다(ADR-018과 같은 원칙).
    access_token_ref  VARCHAR(255) NOT NULL,
    refresh_token_ref VARCHAR(255) NOT NULL,
    -- 응답 expires_in(초, 구글은 3600)으로 계산. resolve가 「30분 미만이면 갱신」을 이 값으로 판정한다.
    -- refresh 만료 컬럼은 V107과 같은 이유로 일부러 없다 — 구글도 만료 시각을 알려주지 않고
    -- (테스트 모드 7일은 정책이지 응답 값이 아니다), 판정은 갱신 거부로만 한다.
    access_expires_at TIMESTAMPTZ  NOT NULL,
    -- 처음엔 연동 시각. 철회 점검이 「언제 마지막으로 구글에 확인했나」를 이 값으로 고른다.
    last_refreshed_at TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ,
    -- USER_UNLINKED | REFRESH_REJECTED. 행을 지우지 않는다(chzzk_channel_links와 같다).
    revoke_reason     VARCHAR(32),
    created_at        TIMESTAMPTZ  NOT NULL
);

-- 다른 계정에 이미 묶인 채널은 DB가 막는다 — 앱 락은 인스턴스가 여럿이면 성립하지 않는다.
CREATE UNIQUE INDEX uq_youtube_links_alive_channel
    ON youtube_channel_links (channel_id) WHERE revoked_at IS NULL;
-- 계정당 살아있는 연동은 하나(uq_chzzk_links_alive_user와 같다).
CREATE UNIQUE INDEX uq_youtube_links_alive_user
    ON youtube_channel_links (user_id) WHERE revoked_at IS NULL;
-- 철회 점검이 "오래 확인 안 한 살아있는 행"을 고른다 — 치지직(access_expires_at)과 축이 다르다.
-- 구글 access는 1시간이라 만료 축으로 고르면 살아있는 행이 늘 전부 걸린다.
CREATE INDEX idx_youtube_links_alive_refreshed
    ON youtube_channel_links (last_refreshed_at) WHERE revoked_at IS NULL;
-- GET 상태·resolve NOT_LINKED는 닫힌 행을 포함한 회원별 최신 행(created_at DESC)을 본다 —
-- 위 부분 인덱스(살아있는 행만)로는 못 타므로 전체 행에 건다. 해제·재연동마다 행이 쌓인다.
CREATE INDEX idx_youtube_links_user_created
    ON youtube_channel_links (user_id, created_at);

COMMENT ON TABLE youtube_channel_links IS '유튜브 채널 연동. 토큰은 secrets 참조만, 상태는 revoked_at·revoke_reason에서 파생 (POK-121)';
COMMENT ON COLUMN youtube_channel_links.channel_id IS '업로드 대상 채널. 동의 시점에 확정되고 UPDATE되지 않는다 — 바꾸는 수단은 재연동(새 행)뿐 (2026-08-24 실측)';
COMMENT ON COLUMN youtube_channel_links.revoke_reason IS 'USER_UNLINKED=사용자 해제·재연동, REFRESH_REJECTED=구글이 갱신을 invalid_grant로 거부(철회·만료)';
