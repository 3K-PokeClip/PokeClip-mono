ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN users.deleted_at IS '탈퇴 시각. 비면 살아있는 회원 (POK-171)';

-- 사진 칸 둘은 항상 짝이다. 한 칸만 차면 /api/auth/me가 500이 되거나(주소 조립 NPE)
-- 사진이 조용히 사라진다(200에 profileImageUrl:null). POK-207 감사가 DB로 반쪽 상태를
-- 직접 만들어 둘 다 재현했고, 탈퇴가 그 칸들을 비우러 오는 첫 코드라 여기서 막는다.
ALTER TABLE users ADD CONSTRAINT ck_users_photo_columns_paired
    CHECK ((profile_photo_key IS NULL) = (profile_photo_updated_at IS NULL));
