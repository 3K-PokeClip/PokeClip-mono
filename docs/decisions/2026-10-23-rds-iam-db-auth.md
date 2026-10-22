# ADR-0006: 사이드카 · janitor 는 RDS IAM 토큰으로 DB 에 붙고, iam 이면 verify-full 과 저장소 커밋 CA 번들을 강제한다

날짜: 2026-10-23(결정 10-22) / 상태: 확정 / 관련: POK-311(이 결정) · POK-304(RDS · DB
사용자 — 권한 목록 전달처), 엣지 계획 I1-1 · I1-2, POK-170 설계(「pgconf 추출은 IAM
때」), ADR-0001(stream_segments DDL 소유권), ADR-0003(media 비특권), 팀 위키
ADR-103 · ADR-104 · ADR-018

## Context

엣지 설계는 Media 의 DB 접속을 RDS IAM 인증으로 정했다(사용자 `media_indexer` ·
`media_janitor`, 둘 다 `rds_iam`). IAM 인증은 DB 비밀번호 대신 AWS 자격 증명으로
서명한 토큰(공식 유효 15분)을 비밀번호 자리에 넣는다. 엣지 계획 I1-1 은 그 접속
코드를, I1-2 는 사이드카 계정의 권한 목록을 이 저장소에 맡겼다.

착수 때 코드는 이랬다.

*   DB 풀이 셋이다 — 사이드카 인덱서 풀 · 사이드카 되감기 발행자 풀 · janitor 풀.
*   DSN 조립이 두 벌이다(`internal/config` 의 `dsn()` 과 janitor 의 `dsnFrom()`).
    변수 · 기본값 · 인코딩이 같고, 둘 다 `POSTGRES_PASSWORD` 필수 · sslmode 기본
    `prefer` 다. `prefer` 는 서버가 TLS 를 받지 않으면 평문으로 내려간다.
*   README 의 janitor 절은 CA 경로를 pgx 가 읽는 `PGSSLROOTCERT` 로 안내하고 있었다.
*   연결 성공을 알리는 고정 로그 줄이 없었다. 배포 뒤 「IAM 으로, TLS 로 붙었다」를
    판정할 수단이 없다.

토큰은 한 번 새면 수용 창 동안 그대로 쓸 수 있는 자격이다. 그래서 TLS 를 어떻게
강제하고 서버 인증서를 무엇으로 검증할지가 같은 결정에 묶였다.

## Decision

1.  **DB 접속 설정을 새 패키지 `media/internal/pgconf` 하나로 추출한다.**
    *   `FromEnv` 가 `POSTGRES_*` · `AWS_REGION` 을 검증하고, `PoolConfig` 가 CA
        파일을 확인해 pgx 풀 설정을 만든다. 세 풀이 모두 이것을 쓴다(사이드카는 한
        번 만들어 두 풀에 `Copy()` 로 나눈다). DSN 조립 두 벌은 지운다.
    *   `POSTGRES_AUTH` 빈 값 · `password` = 지금과 같은 DSN(로컬 compose ·
        `.env.example` 무변경). `iam` = 연결마다 `BeforeConnect` 에서 새 토큰을
        만들어 넣는다. 그 밖 값은 기동 에러다.
    *   AWS 에 기대는 코드는 `iam.go` 한 파일이다(엣지 설계 3-3-1 「AWS 종속은 DB 로그인
        함수 한 곳」). 자격 증명은 SDK 기본 사슬(운영 = 인스턴스 역할)에서 온다.
    *   토큰 생성(자격 증명 조회 포함)에 10초 상한을 `BeforeConnect` 안에 둔다.
        풀의 보충 연결은 `context.Background` 로 불리므로(`jackc/pgx#2545`) 바깥
        기한이 닿지 않는다. 오류 문구에는 토큰 · DSN · AWS 오류 원문을 싣지 않고
        원인의 타입 이름만 싣는다.
    *   사이드카(인덱서 풀)와 janitor 는 Ping 성공 직후 INFO `db_connected` 한 줄을
        남긴다(`pool` · `auth` · `sslmode` · `tls` · `user` · `host` · `port` ·
        `db`). `tls` 는 설정값이 아니라 실제 연결이 TLS 인지다. janitor 에는 이를
        위해 Ping 을 더했다.
1.  **iam 이면 sslmode 는 `verify-full` 만 받고, 약한 조합은 기동 에러로 끝낸다.**
    DB 왕복 전에 거부한다.
    *   iam 의 sslmode 기본값은 `verify-full`. `disable` · `allow` · `prefer` ·
        `require` · `verify-ca` 는 기동 에러.
    *   iam 인데 `POSTGRES_PASSWORD` 가 있으면 기동 에러(값은 오류에 싣지 않는다).
    *   iam 인데 `POSTGRES_SSLROOTCERT` 가 비면 기동 에러 — 이미지 경로를 코드가
        짐작하지 않고 배포 정의가 준다.
    *   password 모드라도 `POSTGRES_SSLROOTCERT` 를 주면 sslmode 는 `verify-ca` ·
        `verify-full` 만 받는다.
    *   `AWS_REGION` 기본 `ap-northeast-2`, 사이드카 · janitor 공통.
1.  **RDS 전 상용 리전 통합 CA 번들을 저장소에 커밋하고, 경로 env
    `POSTGRES_SSLROOTCERT` 를 새로 둔다.**
    *   파일 `media/certs/rds-global-bundle.pem`(출처 AWS truststore 의
        `global/global-bundle.pem`, 2026-10-22 취득, 114장 전부 자체 서명 루트 ·
        중간 CA 0장 · 가장 이른 만료 2061-05-18).
    *   sha256 정본은 `media/Dockerfile` 의 `sha256sum -c` 줄 하나다. 어긋나면
        이미지 빌드가 멈추고 계약 시험(`rdsca_bundle_test.go`)도 실패한다.
    *   이미지 안 경로 `/etc/pokeclip/rds-ca/global-bundle.pem` — root 소유 0644
        (`COPY --chmod=0644`), 상위 디렉터리는 먼저 만들어 755. 사이드카와 janitor
        가 같은 이미지라 번들 한 벌을 둘이 쓴다.
    *   `POSTGRES_SSLROOTCERT` 는 기동 때 절대 경로 · 일반 파일 · PEM 인증서 1장
        이상을 확인한다. 값은 DSN 의 `sslrootcert=` 로 pgx 에 넘긴다.
    *   `PGSSLROOTCERT` 만 있고 `POSTGRES_SSLROOTCERT` 가 비면 기동 에러다. README 의
        `PGSSLROOTCERT` 문장은 고쳤다.
    *   Dockerfile 에 `POSTGRES_SSLROOTCERT` 기본값(`ENV`)은 두지 않는다 — 로컬
        compose 의 password · `prefer` 경로에 끼어들지 않게 배포 정의가 준다.

I1-2 는 `media/README.md` 「사이드카 DB 계정 media_indexer 권한」 절로 답했다 —
표 단위 `SELECT` · `INSERT` · `UPDATE`(`stream_cutoffs` 는 `SELECT` · `INSERT`),
DELETE · DDL 없음, 운영 `ENSURE_SCHEMA=false`. 목록의 정본은
`internal/pgtest/role.go` 이고, 같은 권한만 가진 시험 역할로 사이드카 저장 경로를
실 PG 에서 돌린다.

## Alternatives Considered

**결정 1 — 세 풀이 각자 고치기**

*   두 DSN 조립은 이미 같은 변수 · 기본값 · 인코딩이라 중복이었다. 각자 고치면
    IAM 토큰 경로 · 시간 상한 · sslmode 강제 · 오류 정제를 두 곳에 똑같이 짜야
    하고, AWS 종속도 두 곳으로 퍼진다(엣지 설계 3-3-1 위반). 보안 규칙이 두 벌이면
    한쪽만 바뀐다. POK-170 설계가 「추출은 IAM 때」로 미뤄 둔 바로 그 시점이다.
    기각.

**결정 2 — `prefer` 유지 · `require` 이상 허용**

*   `prefer` 유지 — 서버가 TLS 를 받지 않으면 평문으로 내려가 토큰이 그대로 흐른다.
    토큰은 수용 창 동안 재사용 가능한 자격이다. 기각.
*   `require` 이상 허용 — CA 없는 `require` 는 암호화만 하고 서버 인증서를 검증하지
    않아 중간자를 막지 못한다. `verify-ca` 는 체인만 보고 호스트 이름을 보지
    않는다. RDS 통합 번들은 모든 AWS 고객의 RDS 가 쓰는 공용 CA 라, `verify-ca`
    면 다른 고객의 RDS 인증서도 통과한다. 기각.

**결정 3 — 번들을 어디서 · 무엇을 · 어떤 이름으로**

*   빌드 때 내려받기 — 고정 없이 받으면 빌드마다 내용이 바뀔 수 있다.
    `ADD --checksum` 은 내용을 고정하지만 번들의 실물이 저장소에 없어, 갱신 때의
    검토(이전 번들과 인증서 단위 diff · 새 인증서 확인 · `PRIVATE KEY` 0 확인)가
    커밋 diff 에 남지 않는다. 기각.
*   리전별 번들 — 이미지가 리전 하나에 묶인다. 통합본은 한 파일로 모든 상용 리전을
    덮는다. 통합본이 넓어지는 대가(다른 고객 RDS 의 CA 도 신뢰)는 결정 2 의
    `verify-full` 이 호스트 이름 검증으로 막는다. 기각.
*   `PGSSLROOTCERT` 유지 — pgx 가 `PG*` env 를 기본값으로 몰래 읽으므로 우리 코드가
    기동 때 파일을 확인할 자리가 없고, `POSTGRES_*` 와 이름이 두 갈래가 된다. 이름은
    하나만 살린다. README 가 그동안 이 이름을 안내했으므로 이미 그렇게 설정한
    배포는 조용히 쓰이지 않고 기동 에러로 드러나게 했다. 기각.

## Consequences

*   **좋은 점**
    *   DB 비밀번호라는 저장 비밀이 운영에서 사라진다(팀 위키 ADR-018).
    *   접속 규칙이 한 곳에 있다. 잘못된 조합은 DB 왕복 전에 기동 에러로 끝난다.
    *   배포 판정이 로그 한 줄로 선다 — `db_connected` 이면서 `"auth":"iam"` ·
        `"tls":true`.
    *   실험 RDS 실측(2026-10-22, 서울 PostgreSQL 17.11 db.t4g.micro 1대)에서
        사이드카 · janitor iam 기동 · TLS(`pg_stat_ssl` 전부 `ssl=t`) ·
        janitor 새 연결 재로그인이 통과했다. 거부 갈래 넷(IP 접속 · 다른 CA ·
        `require` · 다른 리전)도 예측한 오류로 끝났다.
    *   변이 40개(토큰 캐시 · 상한 제거 · sslmode 완화 · 번들 고정 제거 · 권한
        회수 등)를 시험이 3/3 잡았다(음성 대조 3/3 통과).
*   **나쁜 점**
    *   의존이 하나 늘었다(`aws-sdk-go-v2/feature/rds/auth`, 코어 판에 맞춤).
    *   번들 갱신은 파일 교체 · Dockerfile sha256 · README 표를 한 커밋에 하고
        이미지를 다시 배포해야 반영된다(번들은 기동 때 읽는다).
    *   로컬 compose 와 CI 의 PG 는 TLS 가 꺼져 있어 iam · verify-full 경로를 돌리지
        않는다. 그 경로는 단위 · 배선 시험(가짜 토큰 함수 · 자체 서명 CA)과 실험
        기록이 맡는다.
    *   첫 IAM 로그인이 약 5.6초 걸렸다(그 뒤 0.5초 안팎, 원인 미측정). 10초 상한은
        토큰 생성만 묶고 서버 인증 시간은 묶지 않는다.
    *   운영을 password 로 되돌리는 것은 `POSTGRES_AUTH` 를 비우는 것만으로 되지
        않는다 — `rds_iam` 을 가진 계정은 IAM 인증이 우선한다. 계정 비밀번호 ·
        `rds_iam` 회수 · 비밀 그릇이 함께 필요하다.
*   **잔여 위험**
    *   **토큰 수용 창은 15분이 아니다.** 실측에서 서명 뒤 19분 30초는 수용,
        20분 30초는 거부였다(1대 · 1회 — 보장 값이 아니고 더 길 수 있다). 새는
        토큰은 그동안 쓸 수 있다는 것이 결정 2 의 전제다.
    *   **정적 키 env 를 코드로 막지 않는다.** SDK 사슬은 env 의 정적 키 ·
        `~/.aws` 공유 파일을 인스턴스 역할보다 앞에 둔다. 코드가 기동 때 가릴 수
        없는 조건이라 배포 정의가 맡는다 — 정적 키 env 0개 · `~/.aws` 마운트 금지 ·
        `AWS_EC2_METADATA_V1_DISABLED=true` · `rds-db:connect` 는 DB 사용자별 ARN.
        `credential_process` 의 stderr 와 SDK 설정 파일 읽기(기동 상한이 덮지
        못한다)도 같은 범주다(README 「DB 접속 운영 전제」).
    *   같은 네트워크 네임스페이스의 컨테이너는 두 사용자 토큰을 다 만들 수 있다
        (엣지 설계 3-3-1 의 알려진 한계).
    *   권한 목록이 막지 못하는 것(README 「잔여 위험」): 표 단위 UPDATE 는 사이드카가
        쓰지 않는 열도 연다 · advisory 잠금 함수는 PUBLIC 이 실행할 수 있어 다른
        계정이 컷오프 주조 잠금 키를 쥐면 주조가 실패를 되풀이한다(회수가 RDS 마스터
        사용자로 되는지 미확인) · `stream_cutoffs` 오염은 DBA 가 손으로 고친다.
    *   실 RDS 에서 사이드카의 연결마다 새 토큰은 직접 재지 못했다(유휴면 새 연결이
        없다 — 같은 pgconf 경로의 프로브가 대신 쟀다). 저장 경로의 권한 충분성은
        실 PG 역할 시험이 맡는다. 인스턴스 역할(IMDS)로 하는 첫 접속은 O7e 몫이다.

## 관련

충돌 · 중복을 확인했다. 아래 어느 것과도 어긋나지 않는다.

*   **ADR-0001**(stream_segments DDL 소유권) — 그 결과 절이 「RDS 권한 설계는 AWS
    배포 전에 결정」으로 남긴 숙제를 이 ADR 이 일부 답한다(`media_indexer` 권한
    목록). DDL 소유 · 열 변경 승인 규칙은 그대로다. 운영은 `ENSURE_SCHEMA=false` 이고
    DDL 은 `media_indexer` 가 아니라 별도 역할이 적용한다.
*   **ADR-0003**(media 비특권) — 번들은 root 소유 0644 이고 사이드카(USER 10001) ·
    janitor(10002)는 읽기만 한다. 비특권 계정이 신뢰 앵커를 바꿔 칠 수 없다.
*   **팀 위키 ADR-103**(Media 상자 기동 모델) — 사이드카는 DB 에 못 닿으면 끝나고
    재기동으로 복구하며, 재기동 범위는 사이드카에 갇힌다. 이 ADR 의 설정 오류
    기동 에러도 같은 재기동 루프에 들어간다. 그래서 「기동했다」가 아니라
    `db_connected` 줄 유무로 판정한다.
*   **팀 위키 ADR-104**(관리 API 비밀번호) — 다루는 비밀이 달라 겹치지 않는다. 같은
    배포 템플릿(`infra/media-host/compose.yml.tmpl`)에 env 가 함께 놓일 뿐이다.
*   **팀 위키 ADR-018**(비밀 저장 원칙) — IAM 인증은 DB 비밀번호라는 저장 비밀
    자체를 없앤다.
*   엣지 계획 I1-1(접속 코드) · I1-2(`ENSURE_SCHEMA=false` · 권한 목록), POK-170
    설계 「pgconf 추출은 IAM 때」.
*   Jira POK-311(이 작업 · 엣지 통지 코멘트), POK-304(RDS · DB 사용자 생성 —
    권한 목록 전달처).
*   코드 `media/internal/pgconf/` · `media/Dockerfile` · `media/certs/`.
*   `media/README.md` 「DB 연결 성공 줄 `db_connected`」 · 「DB 접속 운영 전제」 ·
    「사이드카 DB 계정 media_indexer 권한」 · 「DB 요구 — PostgreSQL 17 이상 ·
    계정 · TLS」 · 「RDS CA 번들」.
*   실험 기록(로컬) — 실험 RDS 실측 판정 · CI 등가 반복과 변이 교정 요약.
