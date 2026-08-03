CREATE TABLE pairing_exchange_attempts (
    id             BIGSERIAL   PRIMARY KEY,
    -- IP 원문을 두지 않는다. rate limit은 동일성만 알면 되고, 청소 작업이
    -- 없어 사실상 영구 보관이 된다. 이 표의 다른 값들처럼 해시로만 남긴다.
    client_ip_hash VARCHAR(64) NOT NULL,
    attempted_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_pairing_attempts_ip_time
    ON pairing_exchange_attempts (client_ip_hash, attempted_at);

COMMENT ON TABLE pairing_exchange_attempts IS
    '교환 rate limit(IP당 분당 5회)용. 실패한 시도도 센다. 청소 작업 없음 — IP 하나가 하루 7,200행';
