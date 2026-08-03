CREATE TABLE pairing_codes (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    -- 코드 원문은 저장하지 않는다. refresh_tokens와 같은 규칙이다.
    code_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

-- 발급 rate limit(계정당 분당 3회)이 이 인덱스로 최근 1분을 센다.
-- 별도 카운터 표를 두지 않는 이유는 발급 이력 자체가 카운터이기 때문이다.
CREATE INDEX idx_pairing_codes_user_created ON pairing_codes (user_id, created_at);

COMMENT ON TABLE pairing_codes IS 'ADR-019: 8자 Crockford · 10분 만료 · 일회용. 청소 작업 없음(알려진 구멍)';
