# ADR-0008: Media 상자 컨테이너는 IMDS 에 닿지 않고, 호스트 대리 창구가 토큰을 가진 사이드카 · janitor 에만 역할 자격을 준다

날짜: 2026-11-15 / 상태: 확정 / supersedes: 볼트 HTTPS 엣지 결정 135 중
Media prod 상자 부분(dev · ECS 작업 역할 경로는 그대로) / 관련: POK-574 ·
POK-551 결정 3 · ADR-0006 · ADR-0003 · 볼트 ADR-103

## Context

prod Media 상자(EC2 한 대)는 MediaMTX · nginx · 사이드카 · 응답기 · janitor 를
docker compose 로 돌린다. 이 가운데 AWS 자격이 필요한 것은 사이드카(S3 업로드 ·
RDS IAM 토큰 · SNS 발행 · KVS 하한 쓰기)와 janitor(RDS IAM 토큰) 둘이다.

착수 때 상자의 IMDS(EC2 인스턴스 메타데이터 서비스 — 상자 안 링크 로컬 주소
169.254.169.254 에서 인스턴스 역할 자격을 내준다) 홉 상한은 2 였다. 홉 상한은
IMDSv2 토큰 응답 패킷의 TTL 이다. 2 면 컨테이너 망을 한 번 건너는 응답도 닿아,
상자 위 **모든** 컨테이너가 역할 자격을 받았다. 인터넷에 노출된 MediaMTX ·
nginx 가 침해되면 그 자리에서 역할 전체(비밀 읽기 · S3 쓰기 · KVS 쓰기 ·
`rds-db:connect`)를 얻는다.

볼트 결정 135(HTTPS 엣지 설계)는 dev 의 컨테이너별 권한을 다루며 「같은
EC2 위 컨테이너는 보안 경계가 아니다」 로 IMDS 일부 차단 · 프록시를
기각하고, prod 는 ECS 작업 역할로 가르기로 했다. Media prod 는 그때와 세
가지가 다르다.

1.  **Media 는 prod 도 EC2 다.** ECS 작업 역할 길이 없다.
1.  **목표가 다르다.** 컨테이너별 권한 분리가 아니라 「AWS 가 필요 없는 인터넷
    노출 컨테이너(MediaMTX · nginx)에 역할 자격을 주지 않기」 다.
1.  **전제를 실측으로 확인했다**(2026-11-15 로컬 · 실험 EC2). 홉 1 이면
    컨테이너의 토큰 PUT 응답이 버려지고, host 망 컨테이너는 받는다. SDK 는
    `AWS_CONTAINER_CREDENTIALS_FULL_URI` 갈래에서 IMDS 공급자를 만들지 않는다.
    망 칸 이웃이 창구에 토큰 없이 닿으면 401 이다.

「같은 EC2 위 컨테이너는 보안 경계가 아니다」 는 여전히 참이다. 그래서 이 결정은
경계가 아니라 **피해 반경 축소**다.

## Decision

1.  **상자 키마다 스위치 `cred_relay` 를 둔다(기본 끔).** `prod/media` tfvars 의
    `instances` 원소 값이다. 켬 키 상자는 IMDS 홉 상한 1 이고, IPv6 IMDS
    엔드포인트는 모든 상자에서 리터럴로 끈다. guard R10 이 홉 값을 리터럴 `1`
    또는 정확히 `each.value.cred_relay ? 1 : 2` 로 묶는다. 끔 키는 지금과 같다 —
    홉 2 · compose 렌더 바이트 동일 · 시작 스크립트 동작 동일.
1.  **역할 자격은 호스트 망의 대리 창구(`cred-relay`)가 토큰을 가진 소비자
    둘에게만 준다.**
    *   소비자 = 사이드카(호출자 `indexer`) · janitor. SDK 의 컨테이너 자격
        엔드포인트 꼴(`AWS_CONTAINER_CREDENTIALS_FULL_URI` +
        `AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE`)로 묻고, IMDS 길은
        `AWS_EC2_METADATA_DISABLED=true` 로 닫는다.
    *   창구 토큰은 시작 스크립트가 기동마다 새로 만들어 tmpfs 에
        둔다(호출자마다 소비자 사본 · 중계 사본 — 0400). 중계는 상수 시간으로
        견준다.
    *   중계는 compose 의 비부착 서비스(`network_mode: host` · `restart:
        on-failure` · cap 없음 · 읽기 전용 루트)이고, 더미 링크 `pokeclip-cred0`
        의 `169.254.170.2:9911` 에서 듣는다.
1.  **만료 의미는 ⓒ 혼합(N = 60분)이다.** 중계는 IMDS 를 직접 읽어 실제 만료를
    안에 두고, 소비자에게는 응답할 때마다 `min(실제 만료, 응답 시각 + 60분)` 을
    보낸다. 늘 실제 만료 이하다.
    *   원천 재조회는 요청이 몰고 간다(배경 고루틴 없음). 인증된 요청이 왔을 때
        마지막 유효분이 없거나 · 실제 만료까지 15분 이하거나 · 마지막 성공 뒤
        30분 이상이고, 직전 원천 호출 뒤 10초가 지났으면 부른다. 최소 간격
        10초는 성공 · 실패와 무관하다. 원천 호출 하나의 상한은 2초다.
    *   IMDS 가 실패하면 마지막 유효분을 실제 만료 1분 전까지 계속 준다(저하
        게이지 `cred_source_degraded`).
1.  **켬 상자의 기동 전 검사는 돌리지 않는다.** 사이드카 이미지 라벨에 판 2 가
    없으면 시작 스크립트가 곧바로 `MEDIA START BLOCKED: cred-relay needs
    sidecar timing v2 label=…` 로 막는다. 기동 전 검사 서비스는 창구 토큰을
    받지 않는다.
1.  **상자 역할에서 `cloudfront-keyvaluestore:GetKey` 를 뺀다.** 상자에서 부르는
    곳이 없다(문 `EdgeBoundsKvs` 는 DescribeKeyValueStore · UpdateKeys 둘).
1.  **중계 이미지는 사이드카 이미지를 재사용한다**(janitor 선례). 그래서 켬
    상자에는 사이드카 하한(이 결정의 머지 이상)이 생긴다.

## Alternatives Considered

**만료 의미**

*   ⓐ 실제 만료 그대로 — AWS 중계(ECS 에이전트 · EKS Pod Identity 에이전트 ·
    Greengrass TES)의 관행이고 중계가 오래 죽어도 소비자가 버틴다. 그러나 우리
    판 소비자(Go SDK endpointcreds)에는 갱신 실패 연장 · 주기 재조회 ·
    ExpiredToken 무효화가 없다. 역할 세션 취소 · IMDS 교대 뒤 소비자가 옛
    키에 「실제 만료 − 5분」(몇 시간일 수 있음) 묶이고, 복구 수단은 소비자
    재시작뿐이다.
    중계는 같은 상자의 로컬 프로세스라 정지가 초 단위여서 이득이 작다. 기각.
*   ⓑ SDK `ec2rolecreds` 그대로 — Retrieve 안에서 실제 만료를 1시간으로 잘라
    중계가 실제 만료를 잃는다. IMDS 장애 내성이 「마지막 원천 호출 뒤 1시간
    미만」 으로 묶이고, 그 상한이 받은 시각에 고정돼 장애 중에 줄어든다. 기각.

**듣는 자리 · 위치**

*   ECS 꼴 DNAT + `route_localnet` — 127/8 경로를 인터페이스 밖으로 여는 sysctl
    과 NAT 규칙의 수명을 우리가 관리해야 한다. 더미 링크는 이름이 정해져 있고
    지우기 쉽다. 기각.
*   별도 systemd 유닛 · 호스트 바이너리 — ADR-103 의 「기동자는 systemd 유닛
    하나」 를 바꾸고, 유닛 간 순서와 바이너리 배포 경로를 새로 만든다. compose
    비부착 서비스는 기동 모델을 바꾸지 않는다. 기각.

**이미지**

*   별도 핀 `cred_relay_image` — 사이드카를 이 결정 아래로 되돌려도 중계가
    살고, IMDS 에 닿는 부품이 의도한 때만 바뀐다. 대신 변수 · 검증 · 루트 전달이
    늘고 user_data 16 KiB 여유를 더 쓴다. 사이드카 하한은 런북 하한 판정과
    끔 키 교체로 다룬다. 기각.

## Consequences

*   **좋은 점**
    *   켬 상자에서 인터넷 노출 컨테이너(MediaMTX · nginx)와 응답기 · 루프백
        송출기 · 기동 전 검사는 역할 자격을 얻지 못한다. ADR-0006 잔여 위험
        「같은 망 칸 컨테이너는 두 DB 사용자 토큰을 다 만든다」 가 「창구 토큰을
        받은 둘(사이드카 · janitor)은 다 만든다」 로 좁아진다.
    *   소비자가 옛 자격에 묶이는 시간이 응답 한 벌당 최대 약 55분으로 묶인다
        (60분 − SDK 캐시 창 5분).
    *   배포 판정이 로그 줄로 선다 — 중계 `cred_served`(`expires_in_s` ≤
        `source_expires_in_s` · ≤ 3600) · 사이드카
        `credentials_ok provider=CredentialsEndpointProvider` · 이웃 칸
        `CRED ISOLATION imds_token=blocked relay=401`.
*   **나쁜 점**
    *   새 부품(중계 — 수백 줄)을 우리가 유지한다. IMDS 응답 꼴이 바뀌면 우리가
        따라간다(시험이 꼴을 고정).
    *   켬 렌더의 압축 user_data 가 16,095 바이트로 16 KiB 여유가 289 바이트다
        (2026-11-16 시험값). 설명은 렌더에 남지 않는 템플릿 주석에 둔다.
    *   **하한이 둘 생긴다.** 이 결정 뒤 모듈로 띄우는 새 상자는 키와 상관없이
        설치 묶음이 이 결정 이상이어야 한다(부트스트랩 필수 파일
        `bin/lib/cred_relay.sh`). 켬 상자는 사이드카도 이 결정 이상이어야 한다.
        판 2 라벨이 없는 사이드카만 위 BLOCKED 줄로 드러나고, 판 2 는 있지만 이
        결정 아래인 사이드카는 중계 진입점이 없어 중계가 종료 127 로 서지
        않는다. 유닛을 멈추고 축약 시작 스크립트(같은 창구 준비 · 같은 compose
        up)를 유닛 밖에서 단독 실행한 실측(POK-574 M2(2026-11-16))에서는
        종료 1 · MediaMTX 만 섰다. 유닛 안에서는
        `Restart=always` · `RestartSec=20` 으로 정리 뒤 20초마다 재시도를
        되풀이하며 방송을 받지 못한다(유닛 정의에서 오는 동작 — 재시도 순환은
        실측하지 않음). 기동 판정에서 먼저 드러난다. 규칙은 교체 런북의
        「대리 창구 하한」 이다.
    *   켬 상자를 제자리 전환할 때는 사이드카 · 묶음이 둘 다 하한 이상이어야
        한다. 제자리 전환은 부트스트랩 필수 파일 확인을 지나지 않아, 옛
        묶음이면 토큰 디렉터리가 생기지 않아 중계 bind 가 실패한다. 유닛 밖
        단독 실행 실측(POK-574 M2(2026-11-16))에서는 compose up 종료 1 ·
        MediaMTX · nginx 를 포함한 모든 서비스가 서지 않았다. 유닛 안에서는
        위와 같이 재시도를 되풀이하며 송출 · 재생까지 멈춘다(재시도 순환은
        실측하지 않음 — 기동 판정에서 드러남).
    *   스위치는 키별이고 IMDS 설정은 떠 있는 상자에도 곧바로 반영된다. 그래서
        켬 · 끔은 새 키 상자로만 바꾼다 — 떠 있는 키의 `metadata_options` 차분은
        plan 단계에서 멈춘다.
*   **잔여 위험(받아들임)**
    *   두 소비자는 역할 자격 **전체**를 받는다(소비자별 권한 분리는 비목표).
    *   중계는 호스트 망에 있다. 엿보기 방어의 전제는 컨테이너에 cap 이 없다는
        것(`cap_drop: ALL` · 망 칸 서비스의 cap 은 CHOWN · SETGID · SETUID 뿐)과
        토큰 디렉터리가 소비자 · 중계에만 붙는다는 것이다. janitor 는
        MediaMTX 와 같은 uid 라 격리 근거는 uid 가 아니라 마운트다.
    *   새어 나간 키는 우리가 보낸 Expiration 과 무관하게 STS 실제 만료까지
        유효하다. 줄이는 수단은 역할 세션 취소뿐이다.
    *   **55분은 철회 뒤 복구의 상한이 아니다.** 세션 취소 뒤에도 IMDS 가 옛
        자격을 내는 동안 중계는 그것을 응답마다 새 60분 상한으로 다시 준다.
        복구는 새 세션 자격을 확보하는 것이고, 가장 확실한 길은 새 키 상자
        교체다. IMDS 가 취소 뒤 새 자격을 언제 내는지는 확인하지 않았다.
    *   `secret-count.sh box` 는 실행 시점의 역할 자격 한 벌만 대조한다. 그 전에
        교대된 자격이 로그에 남았는지는 못 센다 — 그 몫은 중계 코드의 누설
        시험이다.
    *   중계의 `restart: on-failure` 는 `docker kill` · `docker stop` 뒤에 다시
        띄우지 않는다. 그 뒤 복구는 `docker start` 다.

## 관련

충돌 · 중복을 확인했다. 아래 어느 것과도 어긋나지 않는다.

*   **볼트 결정 135**(HTTPS 엣지 — 컨테이너별 권한) — Media prod 상자 부분만
    이 ADR 이 대체한다. dev 상자와 ECS 작업 역할 경로는 그대로다(kty 확인
    2026-11-15).
*   **ADR-0006**(RDS IAM DB 인증) — 정적 키 · `~/.aws` 기각과 충돌하지 않는다
    (엔드포인트 꼴). 운영 전제 `AWS_EC2_METADATA_V1_DISABLED=true` 는 그대로
    두고, 켬 상자에서 `AWS_EC2_METADATA_DISABLED=true` 를 더한다. 판정 줄
    `db_connected`(auth=iam · tls=true)는 새 상자 확인에 그대로 쓴다. 본문은
    고치지 않고 좁아진 잔여 위험은 media-host README 에 적는다.
*   **ADR-0003**(media 비특권) — 중계는 사이드카 이미지의 USER(10001)로 돈다.
    중계 디렉터리를 root:10001 0750 으로 두는 근거다(소유는 root 라 실행 계정은
    이름 자리를 바꿔치지 못한다).
*   **볼트 ADR-103**(Media 상자 기동 모델) — 새 유닛이 없어 모델은 그대로다.
    ADR-103 의 「데몬 재시작 때 엔진이 다시 띄워도 망 칸 합류 실패가 방어선」 은
    host 망인 중계에 해당하지 않는다. 중계의 방어선은 유닛의 「docker 보다 먼저
    down」 순서와 다음 기동의 `--force-recreate` 다.
*   **POK-551 결정 3**(user_data 16 KiB · 한도 여유는 잰다) — 켬 렌더의 최종
    수치는 모듈 시험 `largest_user_data_under_16_kib` 가 켬 키로 매번 잰다.
*   코드: `media/internal/credrelay/` · `media/cmd/cred-relay/` ·
    `infra/media-host/bin/lib/cred_relay.sh` ·
    `infra/media-host/compose.yml.tmpl`(켬 블록) ·
    `infra/terraform/modules/media_host/`(스위치 · 정책) ·
    `infra/terraform/policy/guard/media_role.rego`(R10).
*   문서: `infra/media-host/README.md` 「IMDS 대리 창구」 · 「보안 경계」 ·
    `infra/terraform/runbooks/media-swap.md` 「대리 창구 하한 · 켬 상자」 ·
    `media/README.md` 「cred-relay 명령 · 환경 변수」.
*   실측 기록(저장소 밖) — 로컬 · 실험 EC2 의 홉 표 · SDK 엔드포인트 왕복 ·
    이웃 칸 401.
