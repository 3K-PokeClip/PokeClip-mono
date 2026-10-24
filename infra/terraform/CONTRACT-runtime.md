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
| network → 런타임 1단 | `vpc_id` · `private_subnet_ids` | RDS 서브넷 그룹 · RDS SG 의 VPC | network |
| 런타임 1단 → platform | `rds_sg_id` | platform 이 RDS 인그레스 ← auth · clip 규칙을 만든다 | RDS SG = 런타임, 규칙 = platform |
| 런타임 1단 → platform | `app_secret_arns`(서비스 → 이름 → ARN — auth · clip 의 JWT_SECRET · SECRET_STORE_KEY · INTERNAL_API_TOKEN · OAuth 비밀 · DB 비밀번호 등) | `ecs_service.secrets` 에 엣지 비밀과 합쳐 넣는다. 읽기 정책은 platform(실행 역할) | 그릇 · 값 = 런타임, 읽기 정책 = platform |
| 런타임 1단 → platform | `app_env`(서비스 → 이름 → 값 — DB 엔드포인트 · 큐 URL 등 비밀 아닌 값) | 태스크 env. platform 이 정하는 이름(아래 「platform 이 정하는 env」)이 겹치면 platform 값이 이긴다 | 런타임 |
| 런타임 1단 → platform | `rds_resource_id` | auth · clip 작업 역할의 `rds-db:connect`(대상 = `dbuser:<rds_resource_id>/<사용자>`) — platform `app_db_auth = "iam"` 일 때 | RDS = 런타임, 접속 정책 = platform |
| 런타임 1단 → platform | `app_db_users`(`auth` · `clip` → DB 사용자 이름 — 가칭 `app_auth` · `app_clip`, 3번 POK-407 확정) | 태스크 env `POSTGRES_USER` · `rds-db:connect` 의 사용자 자리(서비스마다 하나, 와일드카드 없음) | DB 사용자 · `rds_iam` · GRANT = 런타임 |
| 런타임 1단 → platform | `app_task_resource_arns`(서비스 → 키 → ARN — 아래 「작업 역할 대상」) | auth · clip 작업 역할 정책 Resource. 키가 없으면(기능 꺼짐) 그 문장을 만들지 않는다 | 자원 = 런타임, 작업 역할 정책 = platform |
| platform → 런타임 · 일꾼 · chat | `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids`(auth · clip · next) | 같은 클러스터 · Cloud Map 네임스페이스(`pokeclip.internal`)에 서비스를 두고, 상대 SG 규칙을 만든다(POK-306 일꾼 · POK-408 chat) | 클러스터 · 네임스페이스 · 서비스 SG = platform, 새 서비스 · 그 규칙 = 그 PR |
| platform → media | `media_proxy_ports`(live · control · playback · health) · `media_health_path` | Media 상자 nginx 포트 · 헬스 경로 — platform 대상 그룹과 같은 값(정본 = platform) | platform |
| 런타임 1단 → media | `rds_endpoint` · `rds_db_name` · `rds_resource_id` · `rds_sg_id` · `rewind_bucket_arn` | 사이드카 · janitor env(주소 · DB 이름) · Media 인스턴스 역할의 `rds-db:connect`(대상 = `rds_resource_id`) · Media ↔ RDS 5432 규칙 한 쌍 · 사이드카 S3(rewind) 업로드 정책(대상 = `rewind_bucket_arn/*`) | RDS · DB 사용자 · GRANT = 런타임, 접속 정책 · SG 규칙 · 업로드 정책 = media |
| 런타임 1단 → media | `media_secret_arns`(INTERNAL_API_TOKEN 등) | Media 인스턴스 역할 읽기 정책 | 그릇 · 값 = 런타임, 읽기 정책 = media |
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
| clip | `segment_bucket_arn` | `arn:aws:s3:::<버킷>` | `s3:DeleteObject` · `GetObject`(`/*`) |

*   auth 의 스트림 키 비밀(이름 = 접두 + UUID, auth 가 만든다)은 런타임
    자원이 아닙니다. platform 이 접두 `pokeclip/prod/stream-key/` 로 정책 ·
    env `STREAM_KEY_SECRET_REF_PREFIX` 를 함께 만듭니다.
*   큐 · 버킷은 SSE-SQS · SSE-S3(AWS 관리 키)를 전제합니다. 고객 관리 KMS
    키면 작업 역할에 그 키 권한이 더 필요합니다 — platform 과 같은 PR 에서
    고칩니다.
*   **조각 창고(`segment_bucket_arn`)에는 CORS 규칙이 있어야 합니다** —
    앱 출처(`https://<app_host>`, 기본 `app.pokeclip.com`)에 `GET` · `HEAD`
    를 허락하는 규칙. 편집기의 트랙별 미리 듣기가 clip 이 서명한 조각 주소를
    브라우저 `fetch` 로 받습니다(POK-389). 창고는 런타임 1단이 만들므로
    규칙도 런타임 몫이고, 없으면 `SegmentRead` 권한이 있어도 브라우저가
    응답을 막아 미리 듣기가 「못 불러왔어요」로만 뜹니다.

### platform 이 정하는 env

auth: `FORWARD_HEADERS_STRATEGY` · `STREAM_KEY_SECRET_STORE_TYPE` ·
`STREAM_KEY_SECRET_RETIRE_FROM`(둘 다 `aws`) · `STREAM_KEY_SECRET_REF_PREFIX` ·
`SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`(값이 있을 때) · `CORS_ALLOWED_ORIGINS`
· `CLIP_BASE_URL` · `COLLECTOR_BASE_URL` · `POSTGRES_USER` · `POSTGRES_AUTH` ·
`POSTGRES_SSLROOTCERT`(IAM 일 때).
clip: `CLOUDFRONT_KEY_PAIR_ID` · `MEDIA_BASE_URL` · `PLAYBACK_COOKIE_DOMAIN` ·
`AUTH_BASE_URL` · `CORS_ALLOWED_ORIGINS` · `PLAYBACK_REQUIRED` ·
`POSTGRES_USER` · `POSTGRES_AUTH` · `POSTGRES_SSLROOTCERT`(IAM 일 때), 비밀
`CLOUDFRONT_PRIVATE_KEY_PEM`. 런타임 `app_env` 에는 이
이름을 넣지 않습니다.

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
1.  DDL 소유 역할로 `stream_*` 스키마를 배치하고, 앱 사용자에게는 DDL 없이
    GRANT 와 `ALTER DEFAULT PRIVILEGES` 를 줍니다.
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
| RDS(인스턴스 · 서브넷 그룹 · 파라미터 그룹) · RDS SG | 접두 `pokeclip-prod-` | 런타임 트랙이 적습니다 |
| rewind 버킷 | 접두 `pokeclip-prod-`(버킷 이름은 전역) | 런타임 트랙이 적습니다 |
| SNS 주제 · SQS 큐 | 접두 `pokeclip-prod-` | 런타임 트랙이 적습니다 |
| 앱 비밀 | `pokeclip/prod/<용도>` | 런타임 트랙이 적습니다 |
| IAM 역할 · 정책 · 인스턴스 프로필 | 접두 `pokeclip-prod-` + 경로 `/pokeclip/prod/` | 런타임 트랙이 적습니다 |

### 단계 직전 점검

런타임 트랙의 운영 단계도 엣지와 같은 직전 점검을 합니다
([단계 직전 점검 런북](runbooks/stage-precheck.md)).

*   SCP 재판정 · 이벤트 기록: M17a 도구(`scripts/precheck/scp-recheck.sh` ·
    `scripts/precheck/events.sh`)를 씁니다.
*   2-3 판정: M17b 도구 `scripts/precheck/principal-check.sh` 를 씁니다
    (POK-365 완료).
