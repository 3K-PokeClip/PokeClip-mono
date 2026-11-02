-- 방송 중 목록(POK-218). 수집기가 재시작한 뒤 붙을 대상을 묻는 창구가 이것을 탄다.
--
-- 부분 색인인 이유 — 담기는 줄이 「지금 방송 중인 것」뿐이라 색인이 표 크기가 아니라
-- 동시 방송 수에 비례한다. 끝난 방송은 계속 쌓이지만 이 색인에는 안 들어온다.
-- started_at DESC인 것은 상한에 닿았을 때 최근 시작한 것부터 남기기 때문이다.
--
-- 🔴 NULLS LAST를 빼지 마라. PostgreSQL은 DESC에서 NULLS FIRST가 기본이라, 이것이 없으면
-- 쿼리의 ORDER BY started_at DESC NULLS LAST와 정렬 순서가 안 맞아 플래너가 이 색인을 쓰고도
-- Sort를 얹거나 아예 버린다(계획 검증 실측: 방송 중 10만 줄에서 Parallel Seq Scan · 버퍼 1,428
-- 대 9). 정본은 BroadcastRepository.findLive이고 둘은 항상 같이 고친다.
CREATE INDEX idx_broadcasts_live_started_at
    ON broadcasts (started_at DESC NULLS LAST)
 WHERE status = 'live';
