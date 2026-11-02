-- 방송 목록(POK-174). 「내가 볼 수 있는 스트리머의 방송을 줄 번호 역순으로」를 받친다.
-- id를 정렬에 넣는 이유는 그것이 이어받기의 기준이기 때문이다 — started_at·ended_at은
-- 뒤늦게 도착한 알림이 갱신하므로(Broadcast.applyStarted·applyEnded) 기준으로 쓸 수 없다.
CREATE INDEX idx_broadcasts_streamer_id_desc ON broadcasts (streamer_id, id DESC);

-- 카드 목록(POK-174). 방송 시간 순 + 이어받기.
-- 기존 idx_jump_cards_stream_seq(stream_id, event_seq)로는 이 정렬을 못 받친다 —
-- event_seq는 마지막으로 바뀐 순서라 카드를 숨기면 순서가 바뀐다.
CREATE INDEX idx_jump_cards_stream_ts ON jump_cards (stream_id, stream_timestamp_ms, id);
