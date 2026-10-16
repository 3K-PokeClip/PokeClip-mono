-- 탈퇴하면 두 연동 표의 채널 이름·번호를 지운다 (POK-341). 행은 지우지 않는다(V107·V109 「행을 지우지 않는다」).
-- 정해 둔 빈 값('')을 쓰지 않고 NULL로 비운다: 빈 값은 진짜 채널처럼 보여 쿼리·화면이 오해할 수 있다.
ALTER TABLE chzzk_channel_links ALTER COLUMN channel_id DROP NOT NULL;
ALTER TABLE chzzk_channel_links ALTER COLUMN channel_name DROP NOT NULL;
ALTER TABLE youtube_channel_links ALTER COLUMN channel_id DROP NOT NULL;
ALTER TABLE youtube_channel_links ALTER COLUMN channel_name DROP NOT NULL;

-- 살아 있는 연동은 여전히 두 칸이 있어야 한다. NULL 허용의 대가로 열린 문을 DB가 닫는다 —
-- 쓰는 쪽 실수 하나로 「채널 없는 살아 있는 연동」이 생기면 수집기는 번호 없는 세션을 연다.
-- 유일 제약(uq_*_alive_channel)은 살아 있는 행에만 걸려 있어 비운 행과 무관하다.
ALTER TABLE chzzk_channel_links ADD CONSTRAINT ck_chzzk_links_alive_has_channel
    CHECK (revoked_at IS NOT NULL OR (channel_id IS NOT NULL AND channel_name IS NOT NULL));
ALTER TABLE youtube_channel_links ADD CONSTRAINT ck_youtube_links_alive_has_channel
    CHECK (revoked_at IS NOT NULL OR (channel_id IS NOT NULL AND channel_name IS NOT NULL));

COMMENT ON COLUMN chzzk_channel_links.channel_name IS '탈퇴하면 NULL(닫힌 행만). 살아 있는 행은 CHECK가 막는다 (POK-341)';
COMMENT ON COLUMN youtube_channel_links.channel_id IS '업로드 대상 채널. 동의 시점에 확정되고 그 뒤로 바뀌지 않는다 — 바꾸는 수단은 재연동(새 행)뿐 (2026-08-24 실측). 예외는 탈퇴 하나: 닫힌 행을 NULL로 비운다 (POK-341)';
