-- 보관함(POK-243)이 편집본마다 「가장 최근 영상」을 찾는다(LATERAL ... ORDER BY id DESC LIMIT 1).
-- 이 색인이 없으면 편집본 한 줄마다 clips 전체를 훑는다. 부분 색인 uq_clips_open_recipe는 진행 중인 줄만 담아 못 쓴다.
CREATE INDEX idx_clips_recipe ON clips (recipe_id, id DESC);
