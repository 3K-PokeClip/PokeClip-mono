-- 방송 보관 기한(ADR-004 — VOD 60일). 만료 판정·조회 제외가 3번(clip) 몫이다(계약 5-2절).
-- NULL = 아직 안 끝난 방송(기한 없음). 값은 종료 처리 때 ended_at + 60일로 채운다.
ALTER TABLE broadcasts ADD COLUMN vod_expires_at TIMESTAMPTZ;

-- 이미 끝난 방송에 기한을 소급한다. 라이브(ended_at IS NULL)는 그대로 NULL.
UPDATE broadcasts SET vod_expires_at = ended_at + INTERVAL '60 days'
 WHERE ended_at IS NOT NULL;

COMMENT ON COLUMN broadcasts.vod_expires_at
    IS '보관 기한(ended_at + 60일, ADR-004). 지난 방송의 세그먼트는 조회하지 않는다(계약 5-2절)';
