# ADR-0006 개정 노트: 사이드카 계정에 M6 만료 · outbox 몫 DELETE 와 컷오프 UPDATE · DELETE 를 준다

날짜: 2026-10-26 / 상태: 확정 / supersedes: ADR-0006
(`2026-10-23-rds-iam-db-auth.md`)의 I1-2 권한 절 — 나머지 절은 그대로 산다 /
관련: POK-196(M6), M6 설계 5절 운영 SQL 행 · 7절 만료 · 15.5 outbox, ADR-0001
(stream_segments DDL 소유권), 프로필 4절 불변식 4(seq 재사용 금지)

## Context

ADR-0006 은 I1-2 에 사이드카 계정 `media_indexer` 의 권한을 표 단위
`SELECT` · `INSERT` · `UPDATE` 로 답했다. `stream_cutoffs` 는 `SELECT` ·
`INSERT` 만이고 DELETE · DDL 은 없다. 「사이드카는 행을 지우지 않는다」가 그
근거였다.

M6 가 이 전제를 바꾼다.

*   **60일 만료(M6-4)** 가 사이드카 안 고루틴으로 돈다(kty K7). 한
    트랜잭션에서 조각 · GAP 행을 지우고, 조각이 0 이 된 세션 행과 그 회차
    outbox 행을 지운다. 컷오프는 다음 생존 자격 행으로 옮기고(UPDATE) 없으면
    지운다(DELETE).
*   **계약9 발행기(M6-8)** 가 `broadcast_lifecycle_outbox` 에 행을 넣고 상태를
    고치며, 끝난 행을 7일 뒤 지운다.
*   **seq 하한(M6-0)** — 행을 지우면 다음 방송이 seq 를 0 부터 다시 쓸 수 있다.
    같은 영구 URL 에 다른 조각이 실려 CDN 의 1년 immutable 사본과 영구히
    어긋난다(불변식 4). 하한 표 `stream_seq_floors` 와 `stream_segments`
    트리거 둘(지우면 하한을 올림 · 하한 아래 INSERT 거부)을 둔다.

그래서 I1-2 의 권한 목록을 다시 정해야 했다.

## Decision

1.  **`media_indexer` 에 아래를 더한다(kty 결정 2026-10-26 (가)).**
    *   DELETE — `stream_segments` · `stream_sessions` ·
        `stream_published_gaps` · `broadcast_lifecycle_outbox`(만료 · outbox
        몫).
    *   `stream_cutoffs` 의 UPDATE · DELETE — 만료의 컷오프 전진 · 제거 몫이다.
        만료 몫 DELETE 에는 이 컷오프 DELETE 가 포함된다.
    *   `broadcast_lifecycle_outbox` 의 SELECT · INSERT · UPDATE(발행기 몫).
    *   `stream_seq_floors` 는 SELECT 만이다. 하한은 `AFTER DELETE` 트리거가
        정의자(표 소유자 — 운영 `media_owner`) 권한으로 올리므로, 사이드카는
        하한을 내리거나 지울 수 없다.
1.  **TRUNCATE · DDL 은 여전히 없다.** 운영은 `ENSURE_SCHEMA=false` 그대로다.
1.  **seq 재사용은 권한이 아니라 하한 트리거가 막는다(불변식 4).** 어느 계정이
    지우든(만료 · clip 탈퇴 정리 · 수동) 트리거가 같은 트랜잭션에서 하한을
    올리고, 하한 아래 seq 의 INSERT 는 23514 `seq_below_floor` 로 거부된다.
1.  **권한은 M6-0 에서 미리 준다.** 쓰는 문장은 M6-4 · M6-8 이 들인다. 운영
    SQL(`30_grants.sql` · `40_verify.sql`)은 M6-0 한 커밋으로 끝나야
    runtime-1a 재실행이 한 번으로 닫힌다(kty P5 = (가)). 같은 이유로
    `media_janitor` 에 `stream_published_gaps` SELECT(③ 포기 조각 갈래)를
    미리 준다.

목록의 정본은 그대로 `media/internal/pgtest/role.go` 하나다. README 블록 ·
`30_grants.sql` · `40_verify.sql` 이 같은 목록인지는 시험이 강제한다.

## Alternatives Considered

**(나) 만료 전용 계정 · 별도 풀**

*   만료만 DELETE 를 가진 계정(예: `media_expiry`)과 사이드카 안 별도 DB 풀을
    두면, 인덱서 풀의 자격이 새도 행을 지우지 못한다.
*   대가는 운영 계정 하나 · IAM 사용자 ARN 하나 · 풀 하나 · 권한 목록 한 벌이
    더 생기는 것이다. 만료는 같은 프로세스 안 고루틴이라 두 자격이 결국 같은
    프로세스 메모리에 있다 — 프로세스가 뚫리면 둘 다 샌다.
*   kty 가 기각했다(2026-10-26 선택창).

## Consequences

*   **좋은 점**
    *   만료 · 발행기가 사이드카 풀 하나로 돈다 — 계정 · 풀 · 목록이 늘지 않는다.
    *   seq 재사용 방지가 권한 경계가 아니라 장부(트리거)에 있어, 지우는 주체가
        늘어도(clip 탈퇴 정리 포함) 같은 규칙으로 막힌다.
*   **나쁜 점**
    *   운영 SQL 적용 · `40_verify.sql` 통과가 M6-0 이후 사이드카 이미지 배포의
        선행 조건이 된다(기동 스키마 확인 — fail-fast).
*   **잔여 위험**
    *   **표 단위 DELETE 는 자격이 새면 장부 행을 지울 수 있게 한다.** 사이드카
        자격(IAM 토큰 · 인스턴스 역할)이 새면 조각 · 세션 · GAP · outbox 행과
        컷오프를 지울 수 있다. 하한 트리거는 지운 뒤의 seq 재사용만 막고, 지운
        행 자체는 되살리지 못한다 — 복구는 백업뿐이다. 이 위험은 ADR-0006 의
        「표 단위 UPDATE 는 쓰지 않는 열도 연다」와 같은 축이고, (나)를 기각하며
        받아들였다.
    *   하한 갱신 함수는 정의자 권한으로 돈다. search_path 를
        `pg_catalog, pg_temp` 로 고정하고 표 이름을 `public` 으로 한정해 덫을
        피한다. `40_verify.sql` 이 정의자 여부 · search_path 를 단언한다.
