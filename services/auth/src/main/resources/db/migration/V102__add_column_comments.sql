-- 한글 이름을 DB에 심는다. ERD 문서(docs/db.html)가 이 주석을 읽어 표시하므로,
-- 새 테이블·컬럼을 만들 때 COMMENT를 함께 쓰면 문서에 한글명이 자동으로 나온다.
-- 안 쓰면 영문명만 나온다 — 틀린 이름이 나오지는 않는다.

COMMENT ON TABLE users IS '회원';
COMMENT ON COLUMN users.id                IS '회원ID';
COMMENT ON COLUMN users.google_sub        IS '구글 고유ID';
COMMENT ON COLUMN users.email             IS '이메일';
COMMENT ON COLUMN users.name              IS '이름';
COMMENT ON COLUMN users.profile_image_url IS '프로필 이미지';
COMMENT ON COLUMN users.created_at        IS '등록일시';
COMMENT ON COLUMN users.updated_at        IS '수정일시';

COMMENT ON TABLE refresh_tokens IS '갱신 토큰';
COMMENT ON COLUMN refresh_tokens.id         IS '토큰ID';
COMMENT ON COLUMN refresh_tokens.user_id    IS '회원ID';
COMMENT ON COLUMN refresh_tokens.token_hash IS '토큰 해시';
COMMENT ON COLUMN refresh_tokens.expires_at IS '만료일시';
COMMENT ON COLUMN refresh_tokens.revoked_at IS '무효화일시';
COMMENT ON COLUMN refresh_tokens.created_at IS '등록일시';
