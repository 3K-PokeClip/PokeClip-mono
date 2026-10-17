-- 썸네일(POK-277). 방송 화면을 사진 한 장으로 찍어 둔 기록. 사진 파일은 완성 영상 창고(CLIPS_BUCKET)의 thumbnails/ 아래에 있다.
--
-- 카드·방송·영상 표에 칸을 더하지 않고 표를 따로 둔다: 카드 줄을 고치면 updated_at이 움직이고 그 표의 순번 규칙(event_seq)과
-- 얽힌다. 사진은 화면의 부가 재료라 원래 줄의 생명주기와 묶지 않는다.
CREATE TABLE thumbnails (
    -- live: 방송 중 최신 화면(대상 = 방송 번호) · card: 하이라이트 카드 시점(대상 = 카드 번호) · clip: 완성 영상(대상 = 영상 번호)
    kind          VARCHAR(8)   NOT NULL,
    target_id     VARCHAR(128) NOT NULL,
    -- 사진이 놓인 키. 일꾼이 올렸다고 보고하기 전에는 비어 있다(주문만 나간 줄).
    s3_key        VARCHAR(512),
    -- 사진이 찍은 장면의 시각. live는 더 늦은 장면만 덮어쓴다(늦게 도착한 옛 보고가 새 사진을 되돌리지 않게).
    captured_at   TIMESTAMPTZ,
    -- 마지막 주문 시각과 횟수. card·clip은 세 번까지만 다시 주문한다(조각이 지워졌으면 영원히 실패한다).
    requested_at  TIMESTAMPTZ,
    attempts      INTEGER      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, target_id),
    CONSTRAINT ck_thumbnails_kind CHECK (kind IN ('live', 'card', 'clip'))
);
