-- 보관 기한이 지난 방송 목록(POK-335, 계약-세그먼트인덱스 6-2). 1번이 10분마다 부른다.
-- 만료 목록은 (기한, 줄 번호)로 이어 받는다. 기한이 빈 줄(안 끝난 방송)은 목록에 안 나오므로 부분 색인이다.
CREATE INDEX idx_broadcasts_vod_expires_at_id ON broadcasts (vod_expires_at, id) WHERE vod_expires_at IS NOT NULL;

-- 지울 구간은 같은 물리 키의 앞 · 뒤 방송으로 정한다. 방송의 자리 = 시작, 없으면 끝, 그것도 없으면 줄이 생긴 시각.
-- 만료 한 줄마다 앞 · 뒤 하나씩 찾으므로 그 식 그대로의 색인이 받친다(V210의 (물리 키, 시작)은 자리표시를 못 받친다).
CREATE INDEX idx_broadcasts_ingest_key_position
    ON broadcasts ((COALESCE(ingest_stream_id, stream_id)), (COALESCE(started_at, ended_at, created_at)), id);
