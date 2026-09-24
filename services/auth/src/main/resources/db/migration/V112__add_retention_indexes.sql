-- 운영 전 잔불 정리 (POK-89). 보관 기한이 지난 행을 10분마다 상한을 두고 지운다(retention 패키지).
-- 청소 조건은 시각 단독이라 기존 인덱스(회원·IP가 앞 칼럼)를 못 탄다. 표가 작을 때는 무관하지만
-- 표가 커진 뒤 청소가 전체 훑기를 하면 스케줄러 스레드 하나를 오래 쥐어 치지직 갱신 틱까지 민다.

-- 회수된 행(revoked_at < 기준)과 회수 안 된 만료 행(revoked_at IS NULL AND expires_at < 기준)은 배타라
-- 부분 인덱스 둘로 나눈다. 합치면 살아있는 행까지 색인에 실린다.
CREATE INDEX idx_refresh_tokens_revoked_at
    ON refresh_tokens (revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX idx_refresh_tokens_expires_alive
    ON refresh_tokens (expires_at) WHERE revoked_at IS NULL;

CREATE INDEX idx_pairing_attempts_attempted_at ON pairing_exchange_attempts (attempted_at);
CREATE INDEX idx_pairing_codes_expires_at ON pairing_codes (expires_at);

-- V105·V106의 주석이 「청소 작업 없음(알려진 구멍)」이라 적혀 있었다. 옛 파일은 체크섬 때문에 못 고쳐
-- 여기서 덮는다. 보관 기간의 정본은 application.yml의 pokeclip.retention.*이다. 여기 숫자를 적지 않는다.
COMMENT ON TABLE pairing_codes IS
    'ADR-019: 8자 Crockford · 10분 만료 · 일회용. 만료 뒤 보관 기간이 지나면 청소가 지운다(POK-89, pokeclip.retention.pairing-codes-keep-for)';
COMMENT ON TABLE pairing_exchange_attempts IS
    '교환 rate limit(IP당 분당 5회)용. 실패한 시도도 센다. 보관 기간이 지나면 청소가 지운다(POK-89, pokeclip.retention.pairing-attempts-keep-for)';
