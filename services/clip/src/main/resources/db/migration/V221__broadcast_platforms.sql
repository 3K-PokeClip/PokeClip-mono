-- 방송마다 켜진 플랫폼 묶음(POK-397). 한 방송을 치지직과 SOOP에 같이 내보내면 줄이 둘이다.
-- 수집기가 그 플랫폼에서 방송을 확인하면 내부 창구 PUT /internal/broadcasts/{streamId}/platforms/{platform}으로 적는다.
--
-- broadcasts.platform(V218, 첫 편지가 정한 값)은 그대로 두고 여기에 더한다. 목록은 둘을 합쳐 보인다.
-- 방송 줄을 지우면(탈퇴 정리) 같이 지워진다(ON DELETE CASCADE). 그래서 정리 코드에 이 표를 지우는 줄이 따로 없다.
-- CHECK는 형식만 본다(clip Platform과 같은 규칙). 새 플랫폼 이름은 마이그레이션 없이 받는다.
-- 줄은 PUT이 올 때만 바뀌고 last_seen_at은 앞으로만 간다. 꺼진 플랫폼도 줄이 남는다(꺼짐 판정은 수집기 관측 시각으로 한다).
-- 색인은 기본 키 하나다. 목록이 방송 번호 묶음으로 읽는데 기본 키 앞자리가 stream_id다.
CREATE TABLE broadcast_platforms (
    stream_id     VARCHAR(128) NOT NULL REFERENCES broadcasts (stream_id) ON DELETE CASCADE,
    platform      VARCHAR(16)  NOT NULL CHECK (platform ~ '^[A-Z0-9_]{1,16}$'),
    first_seen_at TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (stream_id, platform)
);
