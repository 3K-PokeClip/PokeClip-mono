CREATE TABLE stream_keys (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users (id),
    -- stream_token의 SHA-256 hex 64자. 고정 길이지만 CHAR를 쓰지 않는다 —
    -- PostgreSQL의 CHAR는 공백 패딩 때문에 인덱스를 못 탄다(V101과 같은 이유).
    streamid_hash  VARCHAR(64)  NOT NULL UNIQUE,
    -- SecretStore 참조. passphrase도 stream_token 원문도 여기 없다.
    passphrase_ref VARCHAR(255) NOT NULL,
    revoked_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL
);

-- 계정당 살아있는 키는 하나다. 동시에 두 요청이 들어와도 DB가 직접 막는다 —
-- 애플리케이션 락으로는 인스턴스가 여러 개일 때 성립하지 않는다.
-- 폐기된 행은 조건에서 빠지므로 재발급 이력은 그대로 쌓인다.
CREATE UNIQUE INDEX uq_stream_keys_alive_user
    ON stream_keys (user_id) WHERE revoked_at IS NULL;

COMMENT ON TABLE stream_keys IS 'streamid는 해시만, passphrase는 SecretStore 참조만 둔다 (ADR-018)';
COMMENT ON COLUMN stream_keys.revoked_at IS 'NULL이면 살아있다. ADR-018의 active BOOLEAN을 대체한다 — 폐기 시각이 사고 조사에 쓰인다';
