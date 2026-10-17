-- 방송의 물리 키(POK-233, 계약9 ingestStreamId). 편지의 streamId가 방송마다 새 회차 번호로 바뀌면 조각 장부·녹화 경로의
-- stream_id(인제스트 경로, 오늘은 스트림키)와 갈린다. 조각 조회·재생 주소는 이 칸으로, 식별·멱등·순서는 stream_id로 한다.
-- NULL = 이 칸을 싣지 않은 편지로 만든 줄(옛 줄·지금 1번 편지). 읽는 쪽은 COALESCE(ingest_stream_id, stream_id)로 옛 동작을 지킨다.
ALTER TABLE broadcasts ADD COLUMN ingest_stream_id VARCHAR(128);

-- 같은 물리 키의 뒤 방송 찾기(StaleBroadcastReaper). 방송 줄은 끝나도 안 지워져 쌓이므로 정리기 한 바퀴가 방송 중 줄마다
-- 표 전체를 훑지 않게 한다. 식은 정리기 SQL의 COALESCE와 글자까지 같아야 색인을 탄다.
CREATE INDEX idx_broadcasts_ingest_key_started_at
    ON broadcasts ((COALESCE(ingest_stream_id, stream_id)), started_at);
