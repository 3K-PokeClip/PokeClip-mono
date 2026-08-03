-- SecretStore 포트의 PostgreSQL 구현이 쓰는 표.
-- 운영에서 AWS Secrets Manager로 갈아탈 때 이 표만 버리면 되고,
-- stream_keys.passphrase_ref는 그대로 산다.
CREATE TABLE secrets (
    ref        VARCHAR(255) PRIMARY KEY,
    -- AES-256-GCM. 앞 12바이트가 nonce, 나머지가 암호문+태그다.
    -- 평문은 어떤 형태로도 이 표에 들어가지 않는다.
    ciphertext BYTEA        NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

COMMENT ON TABLE secrets IS 'SecretStore 포트의 PG 구현. 값은 AES-256-GCM으로만 저장한다';
COMMENT ON COLUMN secrets.ciphertext IS 'nonce(12B) || ciphertext || tag(16B)';
