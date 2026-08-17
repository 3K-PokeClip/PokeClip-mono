-- 치지직 채널 연동 (POK-93). 회원 하나 ↔ 치지직 채널 하나. 토큰은 SecretStore 참조만.
CREATE TABLE chzzk_channel_links (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users (id),
    -- users/me가 준 값. 사용자 입력을 저장하지 않는다. 로그에도 찍지 않는다.
    channel_id        VARCHAR(64)  NOT NULL,
    channel_name      TEXT         NOT NULL,
    -- 발급·갱신 응답 둘 다에 실려 온다(2026-08-17 실측 — 공식 문서는 갱신에만이라 적었다). 없으면 비어 있다.
    scope             TEXT,
    -- SecretStore 참조. access·refresh 원문은 여기 없다(ADR-018과 같은 원칙).
    access_token_ref  VARCHAR(255) NOT NULL,
    refresh_token_ref VARCHAR(255) NOT NULL,
    -- 응답 expiresIn(초)으로 계산. 스케줄러 선별·상태 파생의 유일한 시각 기준이다.
    -- refresh 만료 컬럼은 일부러 없다 — 치지직이 갱신 때 30일을 리셋하는지 문서에 없어
    -- 추정값이 되고, 추정값을 NOT NULL로 박아두면 언젠가 누가 믿는다. 판정은 갱신 거부(4xx)로만.
    access_expires_at TIMESTAMPTZ  NOT NULL,
    -- 처음엔 연동 시각. GET이 "언제 확인된 정보인지"를 이 값으로 준다.
    last_refreshed_at TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ,
    -- USER_UNLINKED | REFRESH_REJECTED. 행을 지우지 않는다(stream_keys와 같다).
    revoke_reason     VARCHAR(32),
    created_at        TIMESTAMPTZ  NOT NULL
);

-- 다른 계정에 이미 묶인 채널은 DB가 막는다 — 앱 락은 인스턴스가 여럿이면 성립하지 않는다.
CREATE UNIQUE INDEX uq_chzzk_links_alive_channel
    ON chzzk_channel_links (channel_id) WHERE revoked_at IS NULL;
-- 계정당 살아있는 연동은 하나(stream_keys의 uq_stream_keys_alive_user와 같다).
CREATE UNIQUE INDEX uq_chzzk_links_alive_user
    ON chzzk_channel_links (user_id) WHERE revoked_at IS NULL;
-- 스케줄러가 "곧 만료되는 살아있는 행"을 고른다.
CREATE INDEX idx_chzzk_links_alive_expiry
    ON chzzk_channel_links (access_expires_at) WHERE revoked_at IS NULL;
-- GET 상태·resolve NOT_LINKED는 닫힌 행을 포함한 회원별 최신 행(created_at DESC)을 본다 —
-- 위 부분 인덱스(살아있는 행만)로는 못 타므로 전체 행에 건다. 해제·재연동마다 행이 쌓인다.
CREATE INDEX idx_chzzk_links_user_created
    ON chzzk_channel_links (user_id, created_at);

COMMENT ON TABLE chzzk_channel_links IS '치지직 채널 연동. 토큰은 secrets 참조만, 상태는 revoked_at·revoke_reason·access_expires_at에서 파생 (POK-93)';
COMMENT ON COLUMN chzzk_channel_links.revoke_reason IS 'USER_UNLINKED=사용자 해제·재연동, REFRESH_REJECTED=치지직이 갱신을 4xx로 거부(철회·만료)';
