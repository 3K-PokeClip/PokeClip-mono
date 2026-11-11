# ADR-0006 개정 노트: 사이드카 계정에 /dvr 하한 원장 stream_edge_bounds 의 넷을 준다

날짜: 2026-11-04 / 상태: 확정 / supersedes: ADR-0006 개정 노트
(`2026-10-26-media-indexer-m6-delete-grants.md`)의 Decision 1항 — 나머지
절은 그대로 산다 / 관련: POK-525(/dvr 하한), POK-525 설계
3-3(원장 · 권한) · 8-3(위협 모델), ADR-0006(`2026-10-23-rds-iam-db-auth.md`),
프로필 4절 불변식 4(seq 재사용 금지)

## Context

앞 개정 노트는 사이드카 계정 `media_indexer` 의 권한을 표 여섯으로 정했다.
DELETE 는 M6 만료 · outbox 몫뿐이다.

POK-525 가 그 목록에 표를 하나 더한다. `stream_edge_bounds` 는 /dvr 하한
원장이다.

*   **무엇을 적나.** 열린 회차마다 /dvr 하한(엣지가 그 아래 seq 의 되감기
    조각을 막는 값 — 방송 중 시청자가 1시간 DVR 창 밖 조각을 받지 못하게
    한다) 하나다. PK 는 `(stream_id, round_id)` 이고 FK 는 없다.
*   **정본과 사본.** 원장이 정본이고, 엣지 함수가 읽는 CloudFront KVS(엣지
    키-값 저장소)는 그 사본이다.
*   **누가 쓰나.** 사이드카 안 하한 Writer(`internal/rewind/edgebounds`)가
    원장 문장 넷(`internal/index/edge_bounds.go`)을 부른다.
    *   올림 — 열린 회차의 행을 넣거나 `GREATEST` 로 올린다(INSERT ·
        UPDATE).
    *   해제 — 닫힌 회차의 KVS 키를 지운 뒤 해제 시각을 적는다(UPDATE).
    *   정리 — 해제 유예(120초)가 지난 행을 지운다(DELETE). Writer 가 없는
        상자의 감시도 같은 문장을 부른다.
*   **누가 읽나.** Writer 의 닫힌 미해제 행 조회와, 회차 국면 판정식
    (`internal/index/round_playback.go` — 원장 행이 남은 회차는 `vod` 로
    넘어가지 않고 `ending` 에 머문다)이다(SELECT).

앞 노트의 목록에는 이 표가 없다. 그래서 목록을 다시 정해야 했다.

## Decision

1.  **`media_indexer` 에 `stream_edge_bounds` 의 SELECT · INSERT · UPDATE ·
    DELETE 넷을 준다(POK-525 설계 3-3 · 이 노트로 남기는 것은 kty 결정
    2026-11-04 K2 (가)).**
    *   SELECT · INSERT · UPDATE 는 위 올림 · 해제 · 조회 몫이다.
    *   DELETE 는 해제 유예가 지난 행 정리 몫이다. 지우는 행은 이미 해제된
        행이라 그 회차의 KVS 키는 해제 때 먼저 지워졌다 — 지워도 엣지에 남는
        값이 없다.
    *   행을 지워야 국면이 `ending` 에서 `vod` 로 넘어간다. 지우지 않으면
        그 회차 VOD 가 열리지 않는다.
1.  **다른 계정에는 주지 않는다.** `app_clip` · `media_janitor` 는 이 표에
    권한이 없다. clip 탈퇴 정리로 세션 행이 지워진 회차는 Writer 가 「열린
    세션 없음 = 닫힘」 으로 보고 거둔다.
1.  **TRUNCATE · DDL 은 여전히 없다.** 운영은 `ENSURE_SCHEMA=false`
    그대로다.
1.  **하한이 줄지 않는 성질은 SQL 문장에만 둔다(kty 결정 2026-11-04 K3
    (가)).** 올림 문장의 `GREATEST` 가 지키고, 트리거는 넣지 않는다. 하한은
    창 정책이지 접근 통제가 아니다. 1차 통제는 서명 쿠키(시청 권한을 엣지가
    확인하는 값)다. 상자 · 자격이 침해되면 하한을 우회할 수 있다는 것을
    받아들였다.

목록의 정본은 그대로 `media/internal/pgtest/role.go` 하나다. README 블록 ·
`30_grants.sql` · `40_verify.sql` 이 같은 목록인지는 시험이 강제한다.
표마다의 허용 상한은 `media/internal/pgtest/role_test.go` 의
`mediaIndexerAllowance` 다.

## Alternatives Considered

**(나) 하한 단조성을 DB 트리거로도 강제**

*   원장 UPDATE 가 하한을 낮추면 거부하는 트리거를 둔다.
*   사이드카 DB 자격만 샌 경우의 원장 하향은 막는다. 그러나 상자를 침해해
    인스턴스 역할 자격을 얻으면 KVS `UpdateKeys` 로 사본을 직접 고칠 수
    있어, 엣지가 읽는 값은 그대로 열린다.
*   kty 가 기각했다(2026-11-04 K3).

**(다) KVS 쓰기를 상자 밖으로**

*   Writer 를 상자 밖(별도 역할)으로 옮겨 상자 침해가 KVS 에 닿지 않게 한다.
*   재설계라 이 PR 범위를 넘는다. kty 가 기각했다(2026-11-04 K3).

## Consequences

*   **좋은 점**
    *   하한 원장이 사이드카 풀 하나로 돈다 — 계정 · 풀 · 목록이 늘지 않는다.
    *   해제된 행이 쌓이지 않아 국면 판정식이 「원장 행이 있으면 `ending`」
        한 줄로 남는다.
*   **나쁜 점**
    *   운영 SQL 적용 · `40_verify.sql` 통과가 POK-525 이후 사이드카 이미지
        배포의 선행 조건이 된다(기동 스키마 확인 — fail-fast).
*   **잔여 위험**
    *   **자격이 새면 하한을 내리거나 지울 수 있다.** 사이드카 DB 자격이
        새면 표 단위 UPDATE · DELETE 로 원장 하한을 낮추거나 행을 지울 수
        있다. 상자 침해면 KVS 키도 직접 고칠 수 있다. 하한이 내려가면 그
        회차의 창 밖 조각이 엣지를 지나고, 높아지면 시청자가 404 를 받는다.
        위 결정 4 로 받아들였다. 증상과 범위는 media README 「사이드카 DB
        계정 media_indexer 권한」 의 「잔여 위험」 이 적는다.
    *   **해제 전에 지운 행의 KVS 키는 남는다.** Writer 는 지울 키를 원장에서
        읽기 때문이다. 사람 복구 절차가 원장 행을 손으로 지우지 않게 한다
        (media README 「/dvr 하한 사람 복구」).

## 추기 — 뒤에 더해진 권한(2026-11-12)

`stream_timing_gate` SELECT(POK-196 PDT 판 게이트)는 이 노트 뒤에 더해졌고,
결정과 근거는 [M6 개정 노트](2026-10-26-media-indexer-m6-delete-grants.md)의
「추기 — PDT 판 게이트 SELECT (2026-11-11 · POK-196 PDT)」 에 있다. 지금 권한 목록의 정본은
`role.go` `mediaIndexerGrants` 다(시험이 `30_grants.sql` · `40_verify.sql` 과의
일치를 강제한다).
