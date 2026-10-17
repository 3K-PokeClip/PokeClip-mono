-- 시범 운영 측정 재료 (POK-338). 「편집자가 카드를 열어 보고 부적절로 표시한 비율」과 「카드에서 영상까지 걸린 시간」을
-- 정의대로 재려면 세 가지가 없었다: 숨긴 이유, 덮어써지지 않는 행동 기록, 편집본이 어느 카드에서 시작됐는지.
-- 지나간 기록은 나중에 되살릴 수 없어 시범 운영 전에 쌓기 시작한다. 집계 SQL은 services/README.md에 있다.

-- 숨긴 이유. NULL = 이유 없이 숨겼다(옛 호출) 또는 숨기지 않았다. 되돌리면 hidden_at과 함께 지운다.
ALTER TABLE jump_cards ADD COLUMN hidden_reason VARCHAR(16);
ALTER TABLE jump_cards ADD CONSTRAINT ck_jump_cards_hidden_reason
    CHECK (hidden_reason IN ('not_highlight', 'duplicate', 'other'));

-- 카드 행동 기록. 덧붙이기만 한다: jump_cards의 claimed_*·hidden_*는 다시 집거나 되돌리면 덮이고 지워져 이력이 남지 않는다.
-- 「열어 봤다」는 따로 두지 않고 claim으로 갈음한다(2026-10-15 결정). 남이 집은 카드를 연 경우(409)는 안 남는다.
CREATE TABLE jump_card_actions (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 카드가 지워지는 길은 탈퇴 정리뿐이고 그때 기록도 같이 지워야 한다. 정리 코드는 이 표를 따로 지우지 않고
    -- CASCADE에 맡긴다(시험 정리·관리 도구로 카드를 지워도 FK가 막지 않는다).
    card_id BIGINT       NOT NULL REFERENCES jump_cards (id) ON DELETE CASCADE,
    action  VARCHAR(16)  NOT NULL,
    -- 누가(JWT sub). FK 없음: auth users 표를 참조하지 않는다(jump_cards.claimed_by와 같은 처리).
    actor   VARCHAR(128) NOT NULL,
    -- hide에만 쓴다. 다른 행동은 NULL.
    reason  VARCHAR(16),
    -- DB 시계. 카드 표의 claimed_at·hidden_at과 같은 시계라야 둘을 맞대어 볼 수 있다.
    at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_jump_card_actions_action CHECK (action IN ('claim', 'release', 'hide', 'unhide')),
    CONSTRAINT ck_jump_card_actions_reason CHECK (reason IN ('not_highlight', 'duplicate', 'other'))
);

-- 「이 카드의 기록을 시간 순으로」. 집계와 탈퇴 정리가 card_id로 찾는다.
CREATE INDEX idx_jump_card_actions_card ON jump_card_actions (card_id, at);

-- 편집본이 시작된 카드. NULL = 카드 없이 만들었다(보관함·템플릿) 또는 이 칸이 생기기 전에 만들었다.
-- 카드가 지워져도(탈퇴 정리는 편집본을 먼저 지운다) 편집본 저장을 막지 않게 SET NULL.
ALTER TABLE recipes ADD COLUMN source_card_id BIGINT REFERENCES jump_cards (id) ON DELETE SET NULL;
-- 탈퇴 정리가 카드를 지우면 SET NULL이 이 칸으로 recipes를 찾는다. 색인이 없으면 지우는 카드마다 영구 보존 표 전체를 훑는다.
CREATE INDEX idx_recipes_source_card ON recipes (source_card_id);
