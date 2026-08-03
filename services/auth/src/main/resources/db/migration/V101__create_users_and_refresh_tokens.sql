CREATE TABLE users (
    id                BIGSERIAL    PRIMARY KEY,
    google_sub        VARCHAR(255) NOT NULL UNIQUE,
    email             VARCHAR(320) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    profile_image_url VARCHAR(1024),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL
);

-- token_hash는 SHA-256 hex 64자. 고정 길이지만 CHAR를 쓰지 않는다 —
-- PostgreSQL의 CHAR는 공백 패딩 때문에 JPA 매핑에 trim() 우회를 강요하고,
-- 그러면 조회가 인덱스를 못 탄다.
CREATE TABLE refresh_tokens (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
