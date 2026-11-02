-- POK-393: 「열어 봤다」를 집기 대신 편집기 열기로 센다.
-- 옛 넷은 남긴다: 배포 중 옛 서버가 claim·hide를 적는다(정리는 POK-394).
ALTER TABLE jump_card_actions DROP CONSTRAINT ck_jump_card_actions_action;
ALTER TABLE jump_card_actions ADD CONSTRAINT ck_jump_card_actions_action
    CHECK (action IN ('open', 'claim', 'release', 'hide', 'unhide'));
