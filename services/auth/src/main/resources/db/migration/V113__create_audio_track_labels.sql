-- 오디오 트랙 이름 (POK-240). OBS 는 트랙 6개를 항상 다 보내고 트랙에 이름이 없다(계약9 에서 라벨 전달을
-- 폐기했다 — 방송 중 소스를 켜고 끄면 라벨이 안 맞는다). 편집 화면에 「트랙 3」 대신 「디스코드」가 뜨게
-- 스트리머가 설정에서 한 번 적어 두는 표다. 파형·무음 판정(POK-123)은 이것으로 대체돼 폐기됐다.
--
-- 이름을 안 적은 트랙은 행이 없다 — NULL 행을 두지 않는다. 그래서 회원당 0~6행이다.
-- 트랙 번호는 1~6 고정(ADR-017 오디오 트랙 6). 7번을 넣으려는 것은 앱 실수라 DB 가 막는다.
CREATE TABLE audio_track_labels (
    -- CASCADE 인 이유: 이 표는 이력이 아니라 설정이라 회원 행이 사라지면 같이 사라져도 된다(회수 표들과 다르다).
    -- 탈퇴는 회원 행을 지우지 않고 익명화하므로 운영에서 이 갈래를 타는 일은 없다 — 시험 정리에서만 탄다.
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    track_no   SMALLINT    NOT NULL,
    -- 코드 포인트 32자 상한은 앱이 재고, 여기는 바이트 여유만 둔다(이모지 하나가 4바이트).
    label      VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, track_no),
    CONSTRAINT ck_audio_track_labels_track_no CHECK (track_no BETWEEN 1 AND 6),
    CONSTRAINT ck_audio_track_labels_not_blank CHECK (length(label) > 0)
);
