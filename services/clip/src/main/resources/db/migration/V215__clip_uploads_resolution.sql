-- 「확인 필요」 업로드를 사람이 푼다(POK-333).
--
-- 확인 필요(checking)는 올라갔는지 모르는 업로드다. 일꾼이 자동으로 다시 올리지 않으므로 사람이 채널을 보고 결과를 적는다:
-- 「올라갔다 + 영상 번호」면 올림(uploaded), 「안 올라갔다」면 실패(failed, CONFIRMED_NOT_UPLOADED)로 자리를 비운다.
-- 누가 · 언제 풀었는지 남긴다: 두 번 올라간 영상이 나오면 누가 「안 올라갔다」고 적었는지 찾아야 한다.
-- 번호: V214는 POK-338이 쓴다(그 PR이 먼저 머지된다).

ALTER TABLE clip_uploads
    -- 푼 사람(JWT sub). 일꾼이 끝낸 줄은 NULL이다.
    ADD COLUMN resolved_by VARCHAR(128),
    ADD COLUMN resolved_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_clip_uploads_resolved CHECK ((resolved_by IS NULL) = (resolved_at IS NULL));
