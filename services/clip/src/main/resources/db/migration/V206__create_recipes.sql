-- 편집 기록(레시피) 한 벌 = 한 줄 (POK-124). 편집기가 「어디를 어떻게 잘랐는지」를 계약6 모양으로 보내면
-- 그대로 담는다. 완성 영상은 60일 뒤 지워지지만 이 표는 영구 보존한다 — 지우는 문이 없다.
--
-- 칸을 가르는 기준: 검색·연결에 쓰는 것(방송·만든 사람·구간)은 칸으로, 모양이 바뀌는 것(출력·소리·자막)은
-- jsonb로 통째로. jsonb 안의 칸 이름은 계약6과 한 글자도 다르지 않다 — 코드가 계약6 record를 그대로 직렬화한다.
CREATE TABLE recipes (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 같은 서버(clip)의 표라 FK를 건다. 없는 방송엔 레시피를 못 넣는다.
    stream_id       VARCHAR(128) NOT NULL REFERENCES broadcasts (stream_id),
    -- 처음 저장한 사람(JWT sub). FK 없음 — auth users 표를 참조하지 않는다(jump_cards.claimed_by와 같은 처리).
    creator_id      VARCHAR(128) NOT NULL,
    -- 계약6 schemaVersion. 지금은 1뿐이고 저장 검증이 그것만 받는다. 칸으로 두는 이유는 v2가 생기는 날
    -- jsonb를 열어 보지 않고 「어느 모양인가」를 고를 수 있게 하려는 것이다.
    schema_version  INTEGER      NOT NULL,
    -- 고칠 때마다 +1. 렌더 주문(POK-125)이 「몇 판째 레시피로 만들었나」를 가리키는 좌표다.
    recipe_version  INTEGER      NOT NULL DEFAULT 1,
    -- 구간. 둘 다 NULL이면 템플릿(계약6 — cut: null). 하나만 NULL인 줄은 만들 수 없다.
    cut_in_at_ms    BIGINT,
    cut_out_at_ms   BIGINT,
    -- 계약6 outputs[] 그대로. 비율·잘라내기.
    outputs         JSONB        NOT NULL,
    -- 계약6 audio 그대로. 켤 트랙과 음량.
    audio           JSONB        NOT NULL,
    -- 계약6 subtitles 그대로. NULL = 자막 없음(계약6 「생략 = 자막 없음」).
    subtitles       JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- API의 400은 그 경로만 막는다. 다른 경로가 생겨도 여기서 걸린다(jump_cards와 같은 자세).
    CONSTRAINT ck_recipes_cut_both_or_none CHECK ((cut_in_at_ms IS NULL) = (cut_out_at_ms IS NULL)),
    CONSTRAINT ck_recipes_cut_order        CHECK (cut_in_at_ms IS NULL OR cut_in_at_ms < cut_out_at_ms),
    CONSTRAINT ck_recipes_version          CHECK (recipe_version >= 1)
);

-- 「이 방송의 레시피 전부」. 목록 문이 이 순서(id 오름차순 = 만든 순서)로 읽는다.
CREATE INDEX idx_recipes_stream_id ON recipes (stream_id, id);
