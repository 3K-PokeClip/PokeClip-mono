-- 방송 플랫폼(POK-398). 치지직 말고 SOOP 방송도 받기 위한 칸이다. 편지(계약9)의 선택 칸 platform에서 온다.
-- 기존 줄은 전부 치지직 방송이라 기본값으로 채운다. 상수 기본값은 PostgreSQL 11부터 표를 다시 쓰지 않아 잠금이 짧다
-- (20만 줄 실측 1ms, 표 파일 그대로). CHECK는 두지 않는다: 플랫폼이 늘 때 마이그레이션 없이 받으려는 것이고, 형식은 clip이 본다.
-- 색인도 없다. 이 칸으로 거르는 질의가 없다.
ALTER TABLE broadcasts ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT 'CHZZK';
