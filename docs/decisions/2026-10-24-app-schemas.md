# ADR-0007: 앱 표는 서버마다 자기 스키마에 둔다 — DB 하나 공유는 유지한다

날짜: 2026-10-24 / 상태: 확정 / 관련: POK-428(이 결정 · 런타임 1단-나 ①) ·
POK-304(런타임 1단-가 — 운영 SQL · 앱 DB 사용자) · POK-407(서버별 DB 계정) ·
ADR-0001(stream_segments DDL 소유권) · ADR-0006(RDS IAM) · 팀 위키 ADR-022(DB 공유)
· ADR-030(stream_* 소유)

## Context

운영 RDS 의 `pokeclip` DB 하나를 사이드카 · janitor(media 두 계정)와 3번 서버 넷의
앱 사용자 넷(`app_auth` · `app_clip` · `app_chat_collector` · `app_chat_detector`)이
함께 쓴다(팀 위키 ADR-022). 런타임 1단-가는 서버마다 Flyway 로 자기 표를 만들 수
있게 앱 사용자 넷에게 `public` 스키마 CREATE 를 주었다.

그러면 `public` 에 쓸 수 있는 이가 앱 사용자 넷까지 늘어난다. 누가 `public` 에
pg_catalog 함수 · 연산자와 이름이 같고 인자 형이 호출 쪽과 **정확히** 맞는 판을
심으면, 스키마를 한정하지 않은 호출이 암묵 형 변환이 필요한 pg_catalog 판 대신 그
판을 고른다(search_path 가로채기 · CVE-2018-1058 계열). 인자 형이 다른 판은 경로
순서와 무관하게 겨루므로 `pg_catalog` 가 경로 맨 앞이어도 막지 못한다 — 1단-가가
PG 17 에서 실측했다(media `pgtest/searchpath.go` 의 덫). 1단-가는 마스터 · 사이드카
SQL 의 내장 함수 · 연산자를 `pg_catalog` 로 한정해 버텼고, 근본(앱이 `public` 에 쓰지
못하게)은 이 결정으로 넘겼다(kty 2026-10-24).

같은 묶음에서 남은 DB 권한도 정했다 — DB 의 CONNECT · TEMP 는 PUBLIC 기본으로 열려
있었고, advisory 잠금 함수의 EXECUTE(K7)도 PUBLIC 이다.

## Decision

kty 2026-10-24 「추천대로」(K7 부터 K12 모두 설계의 (가)).

1.  **스키마 이름 = DB 계정 이름(K8).** `app_auth` · `app_clip` · `app_chat_collector`
    · `app_chat_detector`. PostgreSQL 의 사용자별 스키마 관례와 같아, 경로 설정이
    빠져도 기본 경로 `"$user", public` 이 자기 스키마를 먼저 찾는다.
1.  **스키마는 마스터가 소유하고 앱에는 `USAGE, CREATE` 만 준다(K9).** 운영
    `10_roles.sql` 이 만든다. 앱이 자기 스키마 권한을 남에게 줄 수 없고, 교차
    `USAGE`(판별 → 수집 스키마)를 런타임 SQL 한곳이 준다.
1.  **앱 세션의 경로는 자기 스키마 하나이고 교차 읽기는 스키마 한정 이름이다(K10).**
    yml 한 키 `pokeclip.datasource.schema` 에서 Hikari `schema`(새 연결마다
    `setSchema`)와 Flyway `schemas` 가 나온다. 교차 읽기는 SQL 글자에
    `public.stream_segments` · `public.stream_sessions` ·
    `app_chat_collector.chat_messages` 로 적는다. 서버 Java 의 맨 이름 함수 호출
    (`pg_advisory_xact_lock(hashtext(?))` 등)은 고치지 않는다 — 경로에 남이 쓸 수
    있는 스키마가 없다.
1.  **앱 사용자의 `public` CREATE 를 거두고, DB CONNECT 는 계정 여섯에만 · TEMP 는
    아무에게도 주지 않는다.** CONNECT 는 `pokeclip` 만 다룬다(K12) — 다른 DB 는
    PUBLIC 기본으로 남는다.
1.  **advisory 함수 EXECUTE 는 회수하지 않는다(K7).** `50_advisory_probe.sql` 은
    갈래를 관측해 기록만 한다.
1.  **DB 하나 공유(ADR-022)는 그대로다.** stream_* 는 `public` 에 남는다(1번 소유 —
    ADR-030).
1.  **dev 공유 DB 는 배포 잡 안의 멱등 단계로 한 번 옮긴다(K11).** 서버 넷 · 일꾼을
    세우고 `public` 을 백업한 뒤 한 트랜잭션으로 옮긴다. 되돌리기는 역이전 스크립트를
    revert 보다 먼저 돌린다(`services/README.md` 「dev 앱별 스키마 이전과 되돌리기」).

**`public` 을 media · 마스터에는 신뢰 스키마로 보면서 앱은 떼는 이유(비대칭).** 이
결정 뒤 `public` 에 쓸 수 있는 이는 마스터 · `media_owner` 뿐이라 media · 마스터 세션의
경로에는 신뢰 스키마만 남는다. 앱을 `public` 에서 떼는 이유는 신뢰가 아니라 **소유
경계**(stream_* 는 1번 표 — 앱이 같은 자리에 표 · 함수를 만들 수 없게)와 **실패 꼴**
(한정이 빠진 교차 읽기가 42P01 로 크게 실패한다 — 조용히 남의 이름을 풀지 않는다)이다.

**Flyway 잠금은 덫 대상이 아니다.** Flyway 12.4 의 PostgreSQL 잠금은
`pg_try_advisory_xact_lock(` · `pg_try_advisory_lock(` · `pg_advisory_unlock(` 에
long 값을 글자로 붙이고, 기준값 `LOCK_MAGIC_NUM = 77431708279161` 이 integer 범위
밖이라 리터럴이 bigint 다(로컬 jar 를 `javap` 로 대조, 2026-10-24). pg_catalog 에
정확히 맞는 bigint 판이 있어 경로와 무관하게 pg_catalog 판이 골린다. 그래서 서버
시험의 덫은 앱이 실제로 부르는 꼴 둘(`pg_advisory_xact_lock(integer)` ·
`hashtext(varchar)`)만 심는다.

## Alternatives Considered

**경로를 거는 자리(D1)**

*   `ALTER ROLE … SET search_path` — dev 는 서버 넷이 계정 하나를 나눠 써 서버마다
    다르게 걸 수 없고, 정본이 런타임 SQL 과 yml 둘로 갈린다. 기각.
*   JDBC URL `currentSchema` — iam 로그인 장치(`DataSourceLoginConfiguration`)가 URL
    쿼리와 `socketTimeout` 밖의 드라이버 속성을 거부한다. 허용 목록을 넓히는 것은
    보안 검토 대상이다. 기각.
*   Hikari `connection-init-sql` — 설정에 SQL 글자가 들어간다. 스키마 둘을 걸 때만
    필요하다(아래 「자기 스키마, public」). 기각.

**경로 모양 — 자기 스키마, public(K10 나)** — stream_* 를 맨 이름 그대로 둘 수 있지만
앱이 `public` 의 위생(40 단언)에 기댄다. 덫 시험의 대조(경로에 `public` 을 더한
컨텍스트)에서 덫이 불린다. 기각.

**스키마를 앱이 소유(K9 나)** — 교차 `USAGE` 를 수집 서버가 새 마이그레이션으로 줘야
하고, 앱이 자기 스키마 권한을 남에게 줄 수 있다. 기각.

**advisory 함수 회수(K7 나)** — 쓰는 계정이 다섯이라(media_indexer · 앱 사용자 넷)
다시 주면 컷오프 열쇠를 막는 위협은 그대로이고, Flyway 판이 잠금 함수를 바꾸면 운영
부팅이 42501 로 멈춘다. RDS 마스터는 pg_catalog 함수 주인이 아니라 회수가 안 될 공산도
크다(추론 — 50 이 관측한다). 기각.

**stream_* 를 media 스키마로 옮김(D10)** — 대칭이지만 ddl.go · 사이드카 · 계약 ·
3번 DDL 승인이 걸려 범위 밖이다. `public` 이 신뢰 스키마가 되므로 지금은 필요 없다.
이월.

**`public` USAGE 회수(D11)** — `public` 에 PUBLIC 표 권한이 없어 이득이 없다. 기각.

## Consequences

*   **좋은 점**
    *   `public` 에 쓸 수 있는 이가 마스터 · `media_owner` 로 줄어 가로채기의
        근본이 닫힌다. 서버의 맨 이름 함수 호출은 고치지 않아도 덫을 고르지
        않는다(auth DbRoleGrantsTest 의 덫 0회 · 대조 1회 이상).
    *   한정이 빠진 교차 읽기가 42P01 로 크게 실패한다 — 시험이 잡는다(clip ·
        수집 서버의 RED 기록).
    *   새 계정은 CONNECT 를 따로 받아야 접속하고, 임시 표를 아무도 만들 수 없다.
*   **대가**
    *   교차 읽기 SQL 이 스키마 한정 이름을 적어야 한다. 새 교차 읽기를 더하는
        사람이 한정을 빠뜨리면 그 경로가 42P01 로 실패한다(조용하지는 않다).
    *   3번 코드에 diff 가 생긴다(yml 세 키 · baseline 삭제 · 교차 읽기 SQL ·
        시험). 3번 리뷰는 받지 않고 통지만 한다(kty 2026-10-24).
    *   dev 배포 한 번에 서버 넷이 수 분 멈춘다. dev 되돌리기는 배포 배타를
        거치고, 배포 기록으로 대기 생략 판정이 안 되면 착수가 기준 시각에서
        최소 110분 늦어진다.
*   **남는 위험**
    *   advisory 함수는 PUBLIC 그대로다 — 다른 계정이 컷오프 열쇠를 쥘 수 있다.
    *   `pokeclip` 밖 DB 의 CONNECT · TEMP 는 PUBLIC 기본이다(명시 연결 상한은
        `media_indexer` 의 20 하나뿐).
    *   운영 적용은 Go 처리기 · 비 rds_superuser 대역으로 시험했다. 첫 운영 적용의
        40 `VERIFY OK` 가 최종 판정이다.
    *   수집 계정이 뚫리면 `chat_messages` 에 RLS 정책을 걸거나 표를 같은
        이름의 뷰로 바꿔, 판별 계정 신분으로 함수를 돌릴 수 있다(정책식 · 뷰
        안의 호출은 부른 쪽 신분이다). 영향은 판별 서버 자기 표
        (`app_chat_detector.chat_metrics`) 안에 갇히고, 수집 서버는 원래 판별
        입력을 쥐고 있다 — 수용한다.

## 관련

*   **팀 위키 ADR-022**(DB 공유) — 이 결정은 공유를 유지하고 그 안의 자리만 나눈다.
    「stream_* 는 public, 앱 표는 자기 스키마」 한 줄 추기는 위키 몫이다.
*   **ADR-0001 · 팀 위키 ADR-030**(stream_* DDL 소유) — stream_* 의 자리 · 소유 ·
    열 변경 승인 규칙은 그대로다.
*   **ADR-0006**(RDS IAM) — 앱 서버의 iam 로그인 장치(db-support)는 바꾸지 않는다.
    Hikari `schema` 는 드라이버 속성이 아니라 iam 허용 목록에 걸리지 않는다.
*   Jira POK-428 · POK-304 · POK-407.
*   코드 `infra/terraform/prod/runtime/sql/` 의 `10_roles.sql` · `40_verify.sql` ·
    `50_advisory_probe.sql` · 서버 넷 `application.yml` ·
    `services/scripts/dev-move-*` · `services/db-support` testFixtures
    (`GrantedRoleDatabase` · `SearchPathTraps` · `DevSchemaMove`).
*   문서 `services/README.md` 「서버별 DB 계정과 권한」 · 「dev 앱별 스키마 이전과
    되돌리기」 · `media/README.md` search_path 가로채기 절.
