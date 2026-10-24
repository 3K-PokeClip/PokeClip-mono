-- 카테고리 사진 주소(POK-462). 치지직 방송 설정의 category.posterImageUrl을 관측마다 적는다. SOOP은 공식 API가 사진을 안 줘서 늘 NULL이다.
-- 값이 없는 칸(NULL) 추가라 표를 다시 쓰지 않는다(메타데이터만). 그래도 강한 잠금을 잠깐 잡으므로 V313과 같이 5초에서 끊는다:
-- 앞에 오래 도는 읽기가 있으면 뒤의 1분 관측 INSERT가 이 잠금 뒤에 줄을 선다. SET LOCAL이라 이 트랜잭션에서만 산다.
-- 칸이 생기기 전 줄은 NULL이다(소급하지 않는다). 옛 인스턴스는 칸을 모른 채 넣고 그 줄도 NULL이다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE broadcast_info ADD COLUMN category_image_url TEXT;

COMMENT ON COLUMN broadcast_info.category_image_url IS '카테고리 사진 주소. 치지직 방송 설정에서만 온다. 설정 조회가 거부된 회차와 SOOP은 NULL';
