-- 올린 프로필 사진 (POK-207). 파일은 S3에 두고 표에는 이름만 남긴다.
ALTER TABLE users ADD COLUMN profile_photo_key        VARCHAR(512);
ALTER TABLE users ADD COLUMN profile_photo_updated_at TIMESTAMPTZ;

-- 회원마다 이름이 하나로 고정돼 덮어쓴다(profile-photos/<회원ID>) — 주인 없는 파일이 생길 수가 없다.
-- 비면 구글이 준 profile_image_url을 쓴다. 둘 다 비면 화면이 기본 그림을 그린다.
COMMENT ON COLUMN users.profile_photo_key        IS '프로필 사진 파일 이름';
-- 사진 주소의 캐시를 가르는 값이다 — 이 시각이 바뀌어야 브라우저가 새 사진을 다시 받는다.
COMMENT ON COLUMN users.profile_photo_updated_at IS '프로필 사진 수정일시';
