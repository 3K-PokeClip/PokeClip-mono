-- 렌더 결과 배치표를 완성 영상 줄에 적는다(POK-196 S-c5 · PDT 설계 r7 3.4 · 8.3).
--
-- 새 꼴 주문의 성공 보고는 결과 맨 위에 placements(조각마다 seq · zeroAtUs · fromUs · toUs · headLeadUs)와 lengthUs(결과 길이 µs)를
-- 싣는다. clip 은 그 둘을 받은 그대로 한 묶음 {"placements": [...], "lengthUs": n} 으로 적고, 완성 사진 자리 · 업로드 장면의 결과 끝을
-- 이 값에서 읽는다(ResultPlacements). 옛 꼴 주문 · 이 칸이 생기기 전 줄은 NULL 이다 — 읽는 쪽이 옛 식으로 떨어진다.
--
-- 널 허용 칸 추가만이다. 옛 코드는 이 칸을 읽지 않는다(되돌려도 옛 코드가 그대로 돈다).
-- 번호: V223 은 POK-525 결정으로 폐기된 영구 빈 번호다. V222 다음이 이 V224 다(ClipsPlacementsMigrationTest).

ALTER TABLE clips
    ADD COLUMN placements JSONB;
