-- SOOP 철회 확인 후보(POK-396). 스케줄러는 살아 있는 행을 last_refreshed_at으로 거르고 정렬해 25명씩 본다.
-- V116은 만료 시각 색인만 두어, 연동이 늘수록 매 틱 살아 있는 행 전체를 훑고 정렬했다(유튜브 V109와 같은 색인).
-- V116을 고치지 않고 새 번호로 둔다: 실계정 시험 때 로컬 DB에 V116이 이미 적용돼 체크섬이 어긋난다.
CREATE INDEX idx_soop_links_alive_refreshed
    ON soop_channel_links (last_refreshed_at) WHERE revoked_at IS NULL;
