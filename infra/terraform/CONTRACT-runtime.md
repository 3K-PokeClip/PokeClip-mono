# 런타임 트랙 인터페이스 계약

이 트리(엣지 트랙)와 prod 런타임 트랙(RDS · 앱 비밀 · SNS/SQS · rewind 버킷 —
별도 트랙)이 주고받는 출력 이름과 소유 경계입니다. 설계 3-6 을 옮긴 것이고,
이 문서가 계약의 정본입니다. 엣지 쪽은 아래 이름과 경계만 약속하고, 런타임
트랙 내부 설계는 그 트랙이 정합니다.

용어:

*   **런타임 1단**: 엣지 platform · media · edge 보다 먼저 적용하는 런타임
    루트입니다(RDS · 앱 비밀 · SNS/SQS · rewind 버킷).
*   **런타임 2단**: edge 뒤에 적용하는 런타임 루트입니다(rewind 버킷 OAC
    정책 · Media ↔ auth SG 규칙).

## 상태 위치

런타임 1단의 상태는 prod 상태 버킷 `pokeclip-tfstate-prod-apne2` 의 키
`prod/runtime/terraform.tfstate` 로 고정합니다. platform · media · edge 는
`terraform_remote_state` 로 이 키를 읽습니다(키는 변수 기본값으로 고정하고,
시험에서는 `override_data` 로 흉내 냅니다).

## 출력과 소유 경계

| 방향 | 이름 | 소비 · 용도 | 소유 |
|---|---|---|---|
| network → 런타임 1단 | `vpc_id` · `private_subnet_ids` · `media_az` · `media_private_subnet_id` | RDS 서브넷 그룹 · RDS SG 의 VPC · RDS 의 AZ(Media 상자와 같은 AZ) · SQL 실행기 서브넷(켜진 동안만) | network |
| 런타임 1단 → platform | `rds_sg_id` | platform 이 RDS 인그레스 ← auth · clip 규칙을 만든다 | RDS SG = 런타임, 규칙 = platform. 예외: `sql_runner_enabled` 가 켜진 동안만 RDS ← SQL 실행기 5432 규칙은 런타임이 가진다 |
| 런타임 1단 → platform | `app_secret_arns`(서비스 → env 이름 → 전체 ARN — auth · clip · chat-collector · chat-detector 의 JWT_SECRET · SECRET_STORE_KEY · INTERNAL_API_TOKEN · OAuth 비밀 등, 그릇 일곱 · 서비스별 목록은 [prod/runtime](prod/runtime/README.md)) | `ecs_service.secrets` 에 엣지 비밀(clip 의 `CLOUDFRONT_PRIVATE_KEY_PEM` = prod/secrets `signing_key_secret_arn`)과 합쳐 넣는다. 읽기 정책은 platform(실행 역할). 앱 서버 DB 자격은 런타임 1단-나가 더한다 | 그릇 · 값 = 런타임, 읽기 정책 = platform |
| 런타임 1단 → platform | `app_env`(DB 엔드포인트 · 큐 URL 등 비밀 아닌 값) | 태스크 env. 큐 수신 스위치 `BROADCAST_INTAKE_ENABLED`(clip · chat-collector 기본 false)와 서비스 주소 `AUTH_BASE_URL` 은 `app_env` 에 없다. 2단 platform 이 넣는다. 스위치가 빠지면 서버는 뜨지만 큐를 읽지 않아 편지가 보관 기간 뒤 사라진다. clip 은 `AUTH_BASE_URL` 이 비면 부팅을 거부하고, chat-collector 는 기본값(localhost)으로 떠서 주소가 틀린 채 돈다 | 런타임 |
| 런타임 1단 → platform | `broadcast_queue_arns`(소비자 clip · chat → 계약9 SQS FIFO 큐 ARN) | clip · chat-collector 의 수신 정책 대상(큐 URL 은 `app_env` 의 `BROADCAST_QUEUE_URL`) | 큐 = 런타임, 수신 정책 = platform |
| 런타임 1단 → media | `rds_endpoint` · `rds_db_name` · `rds_resource_id` · `rds_sg_id` · `rewind_bucket_arn` | 사이드카 · janitor env(주소 · DB 이름) · Media 인스턴스 역할의 `rds-db:connect`(대상 = `rds_resource_id`) · Media ↔ RDS 5432 규칙 한 쌍 · 사이드카 S3(rewind) 업로드 정책(대상 = `rewind_bucket_arn/*`) | RDS · DB 사용자 · GRANT = 런타임(DB 사용자 · GRANT 는 Terraform 이 아니라 `prod/runtime/sql/` 과 [runtime-1a 런북](runbooks/runtime-1a.md)), 접속 정책 · SG 규칙 · 업로드 정책 = media |
| 런타임 1단 → media | `media_secret_arns`(`INTERNAL_API_TOKEN` 하나 — 앱의 그릇과 같은 ARN) | Media 인스턴스 역할 읽기 정책. 한계: 이 열쇠는 auth `/internal/**` 전부에 통한다(전용 토큰은 런타임 1단-나에서 3번과 재합의) | 그릇 · 값 = 런타임, 읽기 정책 = media |
| 런타임 1단 → media | `broadcast_topic_arn` | 사이드카 역할의 `sns:Publish` 대상(계약9 발행 — `MessageDeduplicationId = eventId`) | 주제 = 런타임, 발행 정책 = media |
| 런타임 1단 → edge | `rewind_bucket_{name,arn,regional_domain_name}` | `cdn_media` 의 `rewind-s3` 오리진 | 버킷 = 런타임 |
| edge → 런타임 2단 | `media_distribution_arn` | rewind 버킷 정책의 OAC 허용문(조건 `AWS:SourceArn`) | 버킷 정책 = 런타임 2단 |
| platform · media → 런타임 2단 | `service_sg_ids`(auth · clip) · `media_sg_id` | Media 이그레스 → auth · auth 인그레스 ← Media(내부 resolve), chat-collector · chat-detector ↔ auth · clip 규칙 | SG 규칙 = 런타임 2단 |
| 런타임 → 엣지 시험 | 시험 계정 · 계약9 억제 조건 | 루프백 · T1 부터 T3 · T8 | 런타임 |

clip ↔ auth · RDS ← auth · clip · 로드밸런서 → Media 규칙은 위 표의 런타임
2단 행이 아닙니다(platform · media 가 가짐 — 설계 5-5).

### 응답기 비밀

`media_secret_arns` 의 소비자는 Media 상자의 `ingest-auth`(응답기)
컨테이너입니다. 시작 스크립트가 값을 tmpfs 파일로 렌더하고, 응답기는 **파일
경로**로 받습니다. 환경변수로 넘기지 않습니다.

### DB 접속(IAM DB 인증)

사이드카 · janitor 는 비밀번호 없이 Media 인스턴스 역할로 RDS IAM 토큰을
만들어 접속합니다(설계 3-3-1).

**런타임이 media 기동(O7e) 전에 끝내 둘 것**:

1.  RDS IAM DB 인증을 켭니다.
1.  DB 사용자 `media_indexer` · `media_janitor` 를 만들고 `GRANT rds_iam`
    합니다.
1.  DDL 소유 역할 `media_owner` 로 `stream_*` 스키마를 배치하고, 두 계정에는
    DDL 없이 표 단위 GRANT 만 줍니다. `ALTER DEFAULT PRIVILEGES` 는 쓰지
    않습니다 — 표를 더하는 PR 이 GRANT 도 함께 고칩니다(`prod/runtime/sql/` ·
    [runtime-1a 런북](runbooks/runtime-1a.md)).
1.  `log_connections` 를 켜고 TLS 를 강제합니다(`rds.force_ssl=1`). IAM
    인증 접속은 CloudTrail 에 남지 않아 이 로그로 감사합니다.

**media 가 소유하는 것**:

*   Media ↔ RDS 5432 규칙 한 쌍(platform 의 RDS ← auth · clip 과 대칭).
*   인스턴스 역할의 `rds-db:connect` — dbuser `media_indexer` ·
    `media_janitor` 둘로 한정합니다(와일드카드 없음).
*   사이드카 S3(rewind) 업로드 정책.

### password 대비 갈래

SCP 가 `rds-db:connect` 를 다시 막으면 계약이 이렇게 바뀝니다. 지금은 위 표
그대로입니다. 바꾸는 일은 런타임 · media 를 함께 고치는 별도 PR 입니다.

*   `media_secret_arns` 에 사이드카 · janitor DB 비밀번호 그릇 2개를 더합니다.
    그릇 · 값 · RDS 사용자 비밀번호는 런타임이 맡고, 두 사용자에게서
    `rds_iam` 을 뺍니다. 읽기 정책은 media 가 맡습니다.
*   `rds_resource_id` 와 `rds-db:connect` 정책은 쓰지 않습니다.
*   사용자 분리 · DDL 역할 분리 · `verify-full` · `log_connections` 는
    그대로입니다.

## SG 규칙 규약

*   모든 루트(런타임 트랙 포함)에서 SG 규칙은 별도 자원
    `aws_vpc_security_group_ingress_rule` · `aws_vpc_security_group_egress_rule`
    로만 만듭니다. `aws_security_group` 안의 인라인 규칙은 쓰지 않습니다
    (guard R3).
*   Terraform 이 만든 SG 에는 AWS 기본 이그레스(전부 허용)가 남지 않으므로,
    필요한 이그레스도 모두 별도 규칙으로 적습니다.
*   prod VPC 의 모든 자원(RDS · ECS · 상자 등)은 전용 SG 를 지정하고 VPC 기본
    SG 를 쓰지 않습니다. 기본 SG 는 같은 SG 끼리 전부 허용하고, prod/network 는
    기본 SG 를 다루지 않습니다.

## 이름 · IAM 경로 · default_tags

prod 루트와 같은 규약을 씁니다(계획 3절 「한 계정 규약」). dev 와 prod 가 한
리스 계정에 있어, 이 규약이 dev 쪽 자동 주체 점검에서 prod 주체 · 자원을
가르는 기준이 됩니다(설계 2-3).

*   **이름**: 이름을 정할 수 있는 자원은 접두 `pokeclip-prod-`, Secrets
    Manager 는 `pokeclip/prod/<용도>` 입니다.
*   **IAM 경로**: 런타임 트랙이 만드는 역할 · 정책 · 인스턴스 프로필도 이름
    접두 `pokeclip-prod-` 와 IAM 경로 `/pokeclip/prod/` 를 씁니다. 이
    경로가 dev 쪽 자동 주체 점검에서 prod 주체를 가르는 기준입니다 — 경로를
    쓰지 않은 prod 역할은 점검의 시작 주체로 들어가 점검이 실패할 수
    있습니다. 이 경로의 역할은 시작 집합에서만 빠지고, dev 주체가 그 역할을
    받을 수 있으면 그 역할의 권한으로 판정됩니다(도달 집합).
*   **default_tags**: provider 마다 `Project = pokeclip` ·
    `Environment = prod` · `ManagedBy = terraform` ·
    `TerraformRoot = <루트 경로>` 를 둡니다. 태그를 받는 자원(`tags_all` 이
    있는 자원)에 붙습니다.

### 런타임 자원 이름 패턴

런타임 트랙은 자원 이름 패턴을 이 표에 적어 넘깁니다 — 2-3 판정(dev 자동
주체가 prod 자원에 닿는지 보는 판정)의 입력이 됩니다. 규약을 바꾸는 PR 은
이 표도 함께 고칩니다.

| 자원 | 규약(계획 3절) | 실제 패턴 |
|---|---|---|
| RDS(인스턴스 · 서브넷 그룹 · 파라미터 그룹) · RDS SG | 접두 `pokeclip-prod-` | `pokeclip-prod-db`(인스턴스 · 서브넷 그룹) · `pokeclip-prod-pg17`(파라미터 그룹) · `pokeclip-prod-rds`(SG) |
| rewind 버킷 | 접두 `pokeclip-prod-`(버킷 이름은 전역) | `pokeclip-prod-rewind-apne2`(변수 `rewind_bucket_name` 기본값) |
| SNS 주제 · SQS 큐 | 접두 `pokeclip-prod-` | `pokeclip-prod-broadcast-lifecycle.fifo`(주제) · `pokeclip-prod-broadcast-lifecycle-{clip,chat}.fifo`(큐) · `pokeclip-prod-broadcast-lifecycle-{clip,chat}-dlq.fifo`(DLQ) |
| 앱 비밀 | `pokeclip/prod/<용도>` | `pokeclip/prod/{jwt-secret,secret-store-key,internal-api-token,google-client-secret,youtube-client-secret,chzzk-client-secret,profile-photo-token-secret}` |
| IAM 역할 · 정책 · 인스턴스 프로필 | 접두 `pokeclip-prod-` + 경로 `/pokeclip/prod/` | 역할 · 인스턴스 프로필 `/pokeclip/prod/pokeclip-prod-sql-runner` · 인라인 정책 `pokeclip-prod-sql-runner-master-secret`(SQL 실행기가 켜진 동안만) |
| SQL 실행기(EC2 · SG) | 접두 `pokeclip-prod-` | SG `pokeclip-prod-sql-runner` · 인스턴스 `Name` 태그 `pokeclip-prod-sql-runner`(켜진 동안만) |
| RDS 관리형 마스터 비밀(규약의 예외) | 이름을 RDS 가 정함 | `rds!db-<식별자>` |
| RDS 로그 그룹(규약의 예외) | 이름을 RDS 가 정함 | `/aws/rds/instance/pokeclip-prod-db/postgresql`(보관 30일) |

### 단계 직전 점검

런타임 트랙의 운영 단계도 엣지와 같은 직전 점검을 합니다
([단계 직전 점검 런북](runbooks/stage-precheck.md)).

*   SCP 재판정 · 이벤트 기록: M17a 도구(`scripts/precheck/scp-recheck.sh` ·
    `scripts/precheck/events.sh`)를 씁니다.
*   2-3 판정: 방식을 정리하고 있습니다(POK-365). 그래서 런타임 1단-가의
    점검 묶음 `runtime-1a` 는 SCP 재판정 · 이벤트 기록만 하고 2-3 판정이
    없습니다(미판정 — O7e 전에 POK-365 를 권장). 절차는
    [runtime-1a 런북](runbooks/runtime-1a.md)입니다.
