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
| 런타임 1단 → platform | `app_env`(서비스 → 이름 → 값 — DB 엔드포인트 · 큐 URL 등 비밀 아닌 값) | 태스크 env. platform 이 정하는 이름(아래 「platform 이 정하는 env」)이 겹치면 platform 값이 이긴다. 큐 수신 스위치 `BROADCAST_INTAKE_ENABLED`(서버 기본 false)는 clip 만 `app_env` 가 `"true"`(문자열)로 준다. 스위치가 빠지면 서버는 뜨지만 큐를 읽지 않아 편지가 보관 기간 뒤 사라진다. 서비스 주소 `AUTH_BASE_URL` 은 platform 이 넣는다 — clip 은 비면 부팅을 거부한다. 켜면 auth 내부 호출에 `INTERNAL_API_TOKEN`(`app_secret_arns`)도 쓴다. chat-collector 의 스위치는 배치(POK-306)가 `AUTH_BASE_URL` · `INTERNAL_API_TOKEN` 과 함께 정한다 — 주소가 없으면 기본값(localhost)으로 떠서 틀린 주소로 돈다 | 런타임 |
| 런타임 1단 → platform | `rds_resource_id` | auth · clip 작업 역할의 `rds-db:connect`(대상 = `dbuser:<rds_resource_id>/<사용자>`) — platform `app_db_auth = "iam"` 일 때 | RDS = 런타임, 접속 정책 = platform |
| 런타임 1단 → platform | `app_db_users`(서비스 → DB 사용자 이름 — `auth` `app_auth` · `clip` `app_clip` · `chat-collector` `app_chat_collector` · `chat-detector` `app_chat_detector`, 3번 POK-407 확정) | 태스크 env `POSTGRES_USER` · `rds-db:connect` 의 사용자 자리(서비스마다 하나, 와일드카드 없음). platform 은 auth · clip 만 읽고 모르는 키를 거부하지 않는다 — chat 두 키는 배치(POK-306)가 읽는다. 런타임 1단-가가 낸다(이름은 `prod/runtime/sql/10_roles.sql` 에서 읽음 · kty 2026-10-24) | DB 사용자 · `rds_iam` · GRANT = 런타임(Terraform 이 아니라 `prod/runtime/sql/` 과 [runtime-1a 런북](runbooks/runtime-1a.md)) |
| 런타임 1단 → platform | `app_task_resource_arns`(서비스 → 키 → ARN — 아래 「작업 역할 대상」) | auth · clip 작업 역할 정책 Resource. 키가 없으면(기능 꺼짐) 그 문장을 만들지 않는다 | 자원 = 런타임, 작업 역할 정책 = platform |
| 런타임 1단 → platform | `broadcast_queue_arns`(소비자 clip · chat → 계약9 SQS FIFO 큐 ARN) | chat-collector(POK-306)의 수신 정책 대상(큐 URL 은 `app_env` 의 `BROADCAST_QUEUE_URL`). clip 은 `app_task_resource_arns` 의 `broadcast_queue_arn` 을 쓴다 | 큐 = 런타임, 수신 정책 = platform |
| platform → 런타임 · 일꾼 · chat | `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids`(auth · clip · next) | 같은 클러스터 · Cloud Map 네임스페이스(`pokeclip.internal`)에 서비스를 두고, 상대 SG 규칙을 만든다(POK-306 일꾼 · POK-408 chat) | 클러스터 · 네임스페이스 · 서비스 SG = platform, 새 서비스 · 그 규칙 = 그 PR |
| platform → media | `media_proxy_ports`(live · control · playback · health) · `media_health_path` | Media 상자 nginx 포트 · 헬스 경로 — platform 대상 그룹과 같은 값(정본 = platform) | platform |
| 런타임 1단 → media | `rds_endpoint` · `rds_db_name` · `rds_resource_id` · `rds_sg_id` · `rewind_bucket_arn` | 사이드카 · janitor env(주소 · DB 이름) · Media 인스턴스 역할의 `rds-db:connect`(대상 = `rds_resource_id`) · Media ↔ RDS 5432 규칙 한 쌍 · 사이드카 S3(rewind) 업로드 정책(대상 = `rewind_bucket_arn/*`) | RDS · DB 사용자 · GRANT = 런타임(DB 사용자 · GRANT 는 Terraform 이 아니라 `prod/runtime/sql/` 과 [runtime-1a 런북](runbooks/runtime-1a.md)), 접속 정책 · SG 규칙 · 업로드 정책 = media |
| 런타임 1단 → media | `media_secret_arns`(`INTERNAL_API_TOKEN` 하나 — 앱의 그릇과 같은 ARN) | Media 인스턴스 역할 읽기 정책. 한계: 이 열쇠는 auth `/internal/**` 전부에 통한다(전용 토큰은 런타임 1단-나에서 3번과 재합의) | 그릇 · 값 = 런타임, 읽기 정책 = media |
| 런타임 1단 → media | `broadcast_topic_arn` | 사이드카 역할의 `sns:Publish` 대상(계약9 발행 — `MessageDeduplicationId = eventId`). 발행 권한은 media 역할의 신원 정책이다 — 주제 정책은 계정 루트만 주체로 둔다(기본 정책의 `Principal *` 없음) | 주제 · 주제 정책 = 런타임, 발행 정책 = media |
| 런타임 1단 → edge | `rewind_bucket_{name,arn,regional_domain_name}` | `cdn_media` 의 `rewind-s3` 오리진 | 버킷 = 런타임 |
| edge → 런타임 2단 | `media_distribution_arn` | rewind 버킷 정책의 OAC 허용문(조건 `AWS:SourceArn`) | 버킷 정책 = 런타임 2단 |
| platform · media → 런타임 2단 | `service_sg_ids`(auth · clip · next) · `media_sg_id` | Media 이그레스 → auth · auth 인그레스 ← Media(내부 resolve), chat-collector · chat-detector ↔ auth · clip 규칙 | SG 규칙 = 런타임 2단 |
| 런타임 → 엣지 시험 | 시험 계정 · 계약9 억제 조건 | 루프백 · T1 부터 T3 · T8 | 런타임 |

clip ↔ auth · RDS ← auth · clip · 로드밸런서 → Media 규칙은 위 표의 런타임
2단 행이 아닙니다(platform · media 가 가짐 — 설계 5-5).

*   **auth → chat-collector**: auth 탈퇴 정리가 `COLLECTOR_BASE_URL`
    (`http://chat-collector.pokeclip.internal:8083`)을 부릅니다. 그 SG 짝(auth
    이그레스 · chat-collector 인그레스 8083)은 런타임 2단(POK-306)이
    `service_sg_ids.auth` 로 만듭니다. auth → clip 짝은 platform 이 갖습니다.
*   **SRT 공개**: NLB 인그레스를 /16 보다 넓은 출처(0.0.0.0/0 등)로 여는
    것은 platform `ingest_public_approved = true` 일 때만 됩니다
    (`media_ingest` 검증). S1 게이트 · 응답기(M15)가 먼저 있어야 하고, 8a
    단계의 별도 승인입니다(설계 4-6).
*   **O7d 선행 확인 — POK-407 머지 여부**: auth · clip 의 IAM DB 인증
    코드가 머지되지 않았으면 platform tfvars 에서
    `app_db_auth = "password"` 로 두고, 런타임은 DB 비밀번호 그릇을
    `app_secret_arns` 로 줍니다.

*   **rewind 버킷 이름**: media 는 이름 출력을 따로 받지 않고
    `rewind_bucket_arn`(`arn:aws:s3:::<이름>`)에서 꺼냅니다.
*   **비밀 암호화 키**: 앱 비밀 그릇은 기본 KMS 키(`aws/secretsmanager`)를
    전제합니다. 고객 관리 키로 바꾸면 그 비밀을 읽는 실행 역할(platform)에
    그 키의 `kms:Decrypt` 를 더해야 합니다 — 같은 PR 에서 platform 도
    고칩니다.
*   **응답기 거부 목록**: 응답기(`ingest-auth`)가 읽는 거부 목록 SSM
    Parameter 는 media 소유입니다(런타임 출력 아님).

### 작업 역할 대상

`app_task_resource_arns` 의 키입니다. platform 이 이 키로 auth · clip 작업
역할 문장을 만들고, 키마다 붙는 동작은 코드가 실제로 부르는 것뿐입니다
(근거 파일:행 — [prod/platform README](prod/platform/README.md) 「작업
역할」). 모르는 키 · 와일드카드가 든 ARN 은 platform plan 이 거부합니다.

| 서비스 | 키 | ARN 꼴 | platform 이 붙이는 동작 |
|---|---|---|---|
| auth | `profile_photo_bucket_arn` | `arn:aws:s3:::<버킷>` | `s3:PutObject` · `GetObject` · `DeleteObject`(`<버킷>/*`) |
| clip | `broadcast_queue_arn` | SQS 큐 ARN | `sqs:ReceiveMessage` · `DeleteMessage` |
| clip | `render_queue_arn` · `upload_queue_arn` · `thumbnail_queue_arn` | SQS 큐 ARN | `sqs:SendMessage` |
| clip | `render_dlq_arn` · `upload_dlq_arn` | SQS 큐 ARN | `sqs:ReceiveMessage` · `DeleteMessage` |
| clip | `clips_bucket_arn` | `arn:aws:s3:::<버킷>` | `s3:GetObject` · `PutObject` · `DeleteObject`(`/*`) · `s3:ListBucket`(버킷) |
| clip | `segment_bucket_arn` | `arn:aws:s3:::<버킷>` | `s3:DeleteObject`(`/*`) |

런타임은 지금 clip 의 `broadcast_queue_arn` 하나만 냅니다. 나머지 키는 그
자원을 만드는 런타임 다음 PR(POK-408)이 더합니다(kty 2026-10-23).

*   auth 의 스트림 키 비밀(이름 = 접두 + UUID, auth 가 만든다)은 런타임
    자원이 아닙니다. platform 이 접두 `pokeclip/prod/stream-key/` 로 정책 ·
    env `STREAM_KEY_SECRET_REF_PREFIX` 를 함께 만듭니다.
*   큐 · 버킷은 SSE-SQS · SSE-S3(AWS 관리 키)를 전제합니다. 고객 관리 KMS
    키면 작업 역할에 그 키 권한이 더 필요합니다 — platform 과 같은 PR 에서
    고칩니다.

### platform 이 정하는 env

auth: `FORWARD_HEADERS_STRATEGY` · `STREAM_KEY_SECRET_STORE_TYPE` ·
`STREAM_KEY_SECRET_RETIRE_FROM`(둘 다 `aws`) · `STREAM_KEY_SECRET_REF_PREFIX` ·
`SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`(값이 있을 때) · `CORS_ALLOWED_ORIGINS`
· `CLIP_BASE_URL` · `COLLECTOR_BASE_URL` · `POSTGRES_USER` · `POSTGRES_AUTH` ·
`POSTGRES_SSLROOTCERT`(IAM 일 때).
clip: `CLOUDFRONT_KEY_PAIR_ID` · `MEDIA_BASE_URL` · `PLAYBACK_COOKIE_DOMAIN` ·
`AUTH_BASE_URL` · `CORS_ALLOWED_ORIGINS` · `PLAYBACK_REQUIRED` ·
`POSTGRES_USER` · `POSTGRES_AUTH` · `POSTGRES_SSLROOTCERT`(IAM 일 때), 비밀
`CLOUDFRONT_PRIVATE_KEY_PEM`. 런타임 `app_env` 에는 이 이름을 넣지 않습니다.
clip 의 큐 수신 스위치 `BROADCAST_INTAKE_ENABLED` 는 이 목록에 없고 런타임
`app_env` 가 줍니다. chat-collector 의 스위치 · `AUTH_BASE_URL` 은 그 서비스를
배치하는 POK-306 이 정합니다.

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
*   2-3 판정: M17b 도구 `scripts/precheck/principal-check.sh` 를 씁니다
    (POK-365 완료). `runtime-1a` 도 판정 단계입니다(O7c 뒤 · O7d 앞) —
    런타임 상태 객체가 이 단계부터 대상입니다.
