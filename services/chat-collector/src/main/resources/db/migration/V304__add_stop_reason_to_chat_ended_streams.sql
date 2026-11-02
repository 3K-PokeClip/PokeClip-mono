-- 재시도로 안 풀리는 사유(토큰 거부·동의 철회·구독 거부·송신 오용)로 포기한 방송의 메모.
-- 종료 편지로 닫은 방송(V303의 원래 용도)과 같은 표에 둔다 — 「더 이상 안 걷는 방송」
-- 하나로 보고 치우기(24h)·조회를 하나로 끝낸다(POK-128 PRD). 1번과 협의 중인
-- 「메모를 DB에 둘지 Redis에 둘지」가 정해지면 같이 옮긴다.
--
-- NULL이 곧 「정상 종료」다. 값이 있으면 포기한 것이고, 값은 StopReason 열거 이름이다.
-- 밖(창구)으로는 이 이름을 내보내지 않는다 — needsRelink 하나로 줄인다.
-- 폭 32: 지금 가장 긴 이름이 SESSION_AUTH_REJECTED(21자)다.
ALTER TABLE chat_ended_streams ADD COLUMN stop_reason VARCHAR(32);
