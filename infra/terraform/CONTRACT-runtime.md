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
| 런타임 1단 → platform | `app_secret_arns`(서비스 → env 이름 → 전체 ARN — auth · clip · chat-collector · chat-detector 의 JWT_SECRET · SECRET_STORE_KEY · INTERNAL_API_TOKEN · OAuth 비밀 등, DB 비밀번호는 platform `app_db_auth = "password"` 일 때만 · 그릇 일곱 · 서비스별 목록은 [prod/runtime](prod/runtime/README.md)) | `ecs_service.secrets` 에 엣지 비밀(clip 의 `CLOUDFRONT_PRIVATE_KEY_PEM` = prod/secrets `signing_key_secret_arn`)과 합쳐 넣는다. 읽기 정책은 platform(실행 역할). 앱 서버 DB 자격은 비밀이 아니다 — IAM DB 인증 사용자(`app_db_users` · `prod/runtime/sql/`)로 붙는다. 비밀번호 그릇은 IAM 인증을 못 쓰는 대체 갈래에서만 생긴다(아래 「O7d 선행 확인」) | 그릇 · 값 = 런타임, 읽기 정책 = platform |
| 런타임 1단 → platform | `app_env`(서비스 → 이름 → 값 — DB 엔드포인트 · 큐 URL 등 비밀 아닌 값) | 태스크 env. 키는 앱이 읽는 이름 그대로(아래 「앱이 읽는 env 이름」). platform 이 정하는 이름(아래 「platform 이 정하는 env」)이 겹치면 platform 값이 이긴다. 큐 수신 스위치 `BROADCAST_INTAKE_ENABLED`(서버 기본 false)는 clip 만 `app_env` 가 `"true"`(문자열)로 준다. 스위치가 빠지면 서버는 뜨지만 큐를 읽지 않아 편지가 보관 기간 뒤 사라진다. 서비스 주소 `AUTH_BASE_URL` 은 platform 이 넣는다 — clip 은 비면 부팅을 거부한다. 켜면 auth 내부 호출에 `INTERNAL_API_TOKEN`(`app_secret_arns`)도 쓴다. chat-collector 의 스위치는 배치(POK-306)가 `AUTH_BASE_URL` · `INTERNAL_API_TOKEN` 과 함께 정한다 — 주소가 없으면 기본값(localhost)으로 떠서 틀린 주소로 돈다 | 런타임 |
| 런타임 1단 → platform | `rds_resource_id` | auth · clip 작업 역할의 `rds-db:connect`(대상 = `dbuser:<rds_resource_id>/<사용자>`) — platform `app_db_auth = "iam"` 일 때 | RDS = 런타임, 접속 정책 = platform |
| 런타임 1단 → platform | `app_db_users`(서비스 → DB 사용자 이름 — `auth` `app_auth` · `clip` `app_clip` · `chat-collector` `app_chat_collector` · `chat-detector` `app_chat_detector`, 3번 POK-407 확정) | 태스크 env `POSTGRES_USER` · `rds-db:connect` 의 사용자 자리(서비스마다 하나, 와일드카드 없음). platform 은 auth · clip 만 읽고 모르는 키를 거부하지 않는다 — chat 두 키는 배치(POK-306)가 읽는다. 런타임 1단-가가 낸다(이름은 `prod/runtime/sql/10_roles.sql` 에서 읽음 · kty 2026-10-24) | DB 사용자 · `rds_iam` · GRANT = 런타임(Terraform 이 아니라 `prod/runtime/sql/` 과 [runtime-1a 런북](runbooks/runtime-1a.md)) |
| 런타임 1단 → platform | `app_task_resource_arns`(서비스 → 키 → ARN — 아래 「작업 역할 대상」) | auth · clip 작업 역할 정책 Resource. 키가 없으면(기능 꺼짐) 그 문장을 만들지 않는다 | 자원 = 런타임, 작업 역할 정책 = platform |
| 런타임 1단 → platform | `broadcast_queue_arns`(소비자 clip · chat → 계약9 SQS FIFO 큐 ARN) | chat-collector(POK-306)의 수신 정책 대상(큐 URL 은 `app_env` 의 `BROADCAST_QUEUE_URL`). clip 은 `app_task_resource_arns` 의 `broadcast_queue_arn` 을 쓴다 | 큐 = 런타임, 수신 정책 = platform |
| platform → 런타임 · 일꾼 · chat | `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids`(auth · clip · next) | 같은 클러스터 · Cloud Map 네임스페이스(`pokeclip.internal`)에 서비스를 두고, 상대 SG 규칙을 만든다(POK-408 일꾼 · POK-306 chat) | 클러스터 · 네임스페이스 · 서비스 SG = platform, 새 서비스 · 그 규칙 = 그 PR |
| platform → media | `media_proxy_ports`(live · control · playback · health) · `media_health_path` | Media 상자 nginx 포트 · 헬스 경로 — platform 대상 그룹과 같은 값(정본 = platform) | platform |
| 런타임 1단 → media | `rds_endpoint` · `rds_db_name` · `rds_resource_id` · `rds_sg_id` · `rewind_bucket_arn` | 사이드카 · janitor env(주소 · DB 이름) · Media 인스턴스 역할의 `rds-db:connect`(대상 = `rds_resource_id`) · Media ↔ RDS 5432 규칙 한 쌍 · 사이드카 S3(rewind) 업로드 정책(대상 = `rewind_bucket_arn/*`) | RDS · DB 사용자 · GRANT = 런타임(DB 사용자 · GRANT 는 Terraform 이 아니라 `prod/runtime/sql/` 과 [runtime-1a 런북](runbooks/runtime-1a.md)), 접속 정책 · SG 규칙 · 업로드 정책 = media |
| 런타임 1단 → media | `media_secret_arns`(비밀 전체 ARN 목록 `list(string)` — media_host 변수와 같은 꼴. 지금은 `INTERNAL_API_TOKEN` 그릇 하나, 앱의 그릇과 같은 ARN) | Media 인스턴스 역할 읽기 정책. 한계: 이 열쇠는 auth `/internal/**` 전부에 통한다(전용 토큰은 런타임 1단-나에서 3번과 재합의) | 그릇 · 값 = 런타임, 읽기 정책 = media |
| 런타임 1단 → media | `broadcast_topic_arn` | 사이드카 역할의 `sns:Publish` 대상(계약9 발행 — `MessageDeduplicationId = eventId`). 발행 권한은 media 역할의 신원 정책이다 — 주제 정책은 계정 루트만 주체로 둔다(기본 정책의 `Principal *` 없음) | 주제 · 주제 정책 = 런타임, 발행 정책 = media |
| 런타임 1단 → edge | `rewind_bucket_regional_domain_name` | `cdn_media` 의 `rewind-s3` 오리진 도메인. edge 는 이 하나만 읽는다 — `rewind_bucket_name` · `rewind_bucket_arn` 은 런타임 2단 버킷 정책 몫이다 | 버킷 = 런타임 |
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
*   **O7d 선행 확인 — IAM DB 인증**: PR 343 으로 auth · clip(chat-collector
    포함)도 `db-support` 의 `POSTGRES_AUTH` 를 읽습니다 — platform
    `app_db_auth` 는 기본 `iam` 을 유지합니다. iam 에서는 런타임이 DB
    비밀번호 그릇(`POSTGRES_PASSWORD`)을 주지 않습니다. 그 키가 있으면 앱이
    부팅을 거부하고, platform plan 도 멈춥니다. `password` 로 바꿀 때만
    런타임이 그 그릇을 `app_secret_arns` 로 줍니다.

*   **rewind 버킷 이름**: media 는 이름 출력을 따로 받지 않고
    `rewind_bucket_arn`(`arn:aws:s3:::<이름>`)에서 꺼냅니다.
*   **rewind 버킷 CORS**: 두지 않습니다(사전 요청이 없을 때). 브라우저는
    되감기 · VOD 를 media 배포의 `/dvr/*` · `/vod/*` 로만 받고, CORS 응답
    헤더는 그 비헤이비어의 응답 헤더 정책(media-cors)이 붙입니다. 플레이어
    요청이 사용자 정의 헤더 없는 GET 이라 사전 요청(OPTIONS)이 없다는 전제이고
    (허용 헤더 기본 `["range"]` — 빈 목록은 provider 가 못 받음 · 사전 요청 0 전제는 G14 그대로), T1 · T10 에서 OPTIONS 가 생기면 다시
    봅니다. `prod/runtime/rewind_bucket.tf` 머리 주석 「edge 에 묻는다」 의
    답입니다.
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

런타임은 지금 clip 의 `broadcast_queue_arn` 하나만 냅니다. 나머지 키는 그
자원을 만드는 런타임 다음 PR(POK-408)이 더합니다(kty 2026-10-23).

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
`FORWARD_HEADERS_TRUSTED_PROXIES`(network `vpc_cidr`) · `CORS_ALLOWED_ORIGINS`
· `CLIP_BASE_URL` · `COLLECTOR_BASE_URL` · `POSTGRES_USER` · `POSTGRES_AUTH` ·
`POSTGRES_SSLROOTCERT`(IAM 일 때).
clip: `CLOUDFRONT_KEY_PAIR_ID` · `MEDIA_BASE_URL` · `PLAYBACK_COOKIE_DOMAIN` ·
`AUTH_BASE_URL` · `CORS_ALLOWED_ORIGINS` · `COLLECTOR_BASE_URL` ·
`PLAYBACK_REQUIRED` · `POSTGRES_USER` · `POSTGRES_AUTH` ·
`POSTGRES_SSLROOTCERT`(IAM 일 때), 비밀 `CLOUDFRONT_PRIVATE_KEY_PEM`. 런타임
`app_env` 에는 이 이름을 넣지 않습니다.
clip 의 큐 수신 스위치 `BROADCAST_INTAKE_ENABLED` 는 이 목록에 없고 런타임
`app_env` 가 줍니다. chat-collector 의 스위치 · `AUTH_BASE_URL` 은 그 서비스를
배치하는 POK-306 이 정합니다.

### 앱이 읽는 env 이름

`app_env` · `app_secret_arns` 의 키는 앱이 읽는 이름 그대로여야 합니다. 앱은
모르는 이름을 오류 없이 무시하고 기본값으로 뜹니다. 예를 들어 Media
사이드카의 이름인 `POSTGRES_HOST` 를 auth · clip 에 넣으면, 앱은 `DB_HOST` 를
찾지 못해 `localhost` 로 붙습니다.

`app_env` 는 평문 태스크 env 라 platform 이 plan 때 비밀을 싣는 꼴을
거부합니다(`prod/platform/remote_state.tf` 의 런타임 계약 검사).

*   이름: `^[A-Z][A-Z0-9_]*$` 꼴만 받고, `PASSWORD` · `PASSWD` ·
    `PASSPHRASE` · `SECRET` · `TOKEN` · `KEY` · `PEM` · `CREDENTIAL` 이
    이름 어디에 들어도 거부합니다. 비밀이 아닌 계약 이름 다섯
    (`STREAM_KEY_RESOLVE_ALLOWED_CIDRS` · `STREAM_KEY_SECRET_*` 셋 ·
    `CLOUDFRONT_KEY_PAIR_ID`)은 검사 식 안의 예외입니다. 그 밖에 비밀이
    아닌데 걸리는 이름은 platform 변수 `app_env_non_secret_names` 에
    적습니다.
*   운반 이름: `SPRING_APPLICATION_JSON` · `JAVA_TOOL_OPTIONS` ·
    `JDK_JAVA_OPTIONS` · `JAVA_OPTS` 는 받지 않습니다.
*   값: 다음 꼴을 거부합니다.
    *   URL 자격 증명(`scheme://user:pass@` · `https://<토큰>@`).
    *   키 이름에 `passw` · `pwd` · `secret` · `token` 이 든 쿼리 ·
        프래그먼트 매개변수(`?password=` · `#access_token=`, 대소문자 무관).
    *   속성 이름에 `secret` · `password` · `token` 이 든 `-D` 속성.
*   값 검사는 위 낱말만 봅니다. `?api_key=…` · `-Ddb.passwd=…` ·
    `-Dapi.key=…` 처럼 다른 낱말을 쓴 비밀은 통과하므로 검사에 기대지
    말고 처음부터 `app_secret_arns` 로 넘깁니다. 거꾸로 쿼리나
    프래그먼트에 `user:x@` 꼴이 든 정상 값과 `token_type` 같은 비밀 아닌
    키는 걸릴 수 있습니다(알려진 오탐 — 그런 값은 `app_secret_arns` 로
    옮깁니다).

비밀은 모두 `app_secret_arns` 로 넘깁니다.

근거는 PR 343 머지 뒤 develop 의
`services/auth` · `services/clip` 의 `src/main/resources/application.yml`,
`services/db-support`, `web/Dockerfile` 입니다. 「yml 기본값」 은 자리
표시자(`${이름:기본값}`)의 기본값이고, 「없음」 은 기본값이 없어 env 가
없으면 자리 표시자 글자가 그대로 값이 된다는 뜻입니다. platform 이 넣는
이름은 위 「platform 이 정하는 env」 이고, 여기에는 헷갈리기 쉬운 셋
(`POSTGRES_AUTH` 줄 · 신뢰 대역 · clip 수집기 주소)만 다시 적습니다.

| 서비스 | 이름 | 넣는 쪽 | yml 기본값 | 비고 |
|---|---|---|---|---|
| auth · clip | `DB_HOST` | `app_env` | `localhost` | RDS 엔드포인트 호스트. 빠뜨리면 `localhost` 로 붙어 DB 연결이 실패한다 |
| auth · clip | `DB_PORT` | `app_env`(선택) | `5432` | |
| auth · clip | `POSTGRES_DB` | `app_env` | 없음 | DB 이름 |
| auth · clip | `POSTGRES_PASSWORD` | `app_secret_arns`(`password` 방식일 때만) | 없음 | `password` 방식에서 필수. platform 기본 `iam` 에서는 넣지 않는다 — 있으면 부팅 거부(아래 `POSTGRES_AUTH` 행) |
| auth · clip | `POSTGRES_AUTH` · `POSTGRES_SSLROOTCERT` | platform | `password` · 빈 값 | `db-support` 가 읽는다(PR 343 — chat-collector 도 같다, `services/README.md` 「DB 로그인 방식」). `iam` 이면 `POSTGRES_PASSWORD` 가 있을 때 부팅 거부(`DataSourceLoginConfiguration.rejectLeftoverPassword`). platform 은 iam 일 때 이미지 CA 번들 `/etc/pokeclip/rds-ca/global-bundle.pem` 을 넣는다 |
| auth · clip | `JWT_SECRET` · `INTERNAL_API_TOKEN` | `app_secret_arns` | 빈 값 | 비면 부팅 거부. 두 서비스가 같은 값 |
| auth | `SECRET_STORE_KEY` | `app_secret_arns` | 빈 값 | 비면 부팅 거부. `STREAM_KEY_SECRET_STORE_TYPE=aws` 여도 필수(연동 토큰이 쓴다) |
| auth | `GOOGLE_CLIENT_ID` · `GOOGLE_REDIRECT_URI` | `app_env` | 빈 값 · `http://localhost:3000/auth/callback` | 비밀 `GOOGLE_CLIENT_SECRET` 은 `app_secret_arns`. ID · 비밀이 비면 부팅 거부. 주소는 기본값이 로컬이라 prod 는 꼭 넣는다 |
| auth | `CHZZK_CLIENT_ID` · `CHZZK_REDIRECT_URI` | `app_env` | 빈 값 | 비밀 `CHZZK_CLIENT_SECRET` 은 `app_secret_arns`. 셋이 한 덩어리 — 하나만 빠져도 부팅 거부 |
| auth | `YOUTUBE_CLIENT_ID` · `YOUTUBE_REDIRECT_URI` | `app_env` | 빈 값 | 비밀 `YOUTUBE_CLIENT_SECRET` 은 `app_secret_arns`. 치지직과 같다 |
| auth | `SOOP_CLIENT_ID` · `SOOP_REDIRECT_URI` | `app_env` | 빈 값 | 비밀 `SOOP_CLIENT_SECRET` 은 `app_secret_arns`. 치지직과 같다 |
| auth | `PROFILE_PHOTO_S3_BUCKET` · `PROFILE_PHOTO_BASE_URL` | `app_env` | 빈 값 | 버킷이 비면 사진 기능이 꺼진다. 버킷은 「작업 역할 대상」 `profile_photo_bucket_arn` 과 같은 것 |
| auth | `PROFILE_PHOTO_TOKEN_SECRET` | `app_secret_arns` | 빈 값 | 버킷이 있는데 비면 부팅 거부. `JWT_SECRET` 과 다른 값 |
| auth | `STREAM_KEY_RESOLVE_ALLOWED_CIDRS` | `app_env` | 빈 값 | resolve 전용 창구(계약4 4C)의 허용 대역. 아래 토큰이 있으면 필수 — 비면 부팅 거부, platform plan 도 멈춘다 |
| auth | `STREAM_KEY_RESOLVE_TOKEN` | `app_secret_arns` | 빈 값 | 비면 resolve 전용 창구가 닫힌다(auth 는 뜬다) |
| auth | `FORWARD_HEADERS_TRUSTED_PROXIES` | platform | Boot 기본 신뢰 대역 | 값 = network `vpc_cidr`. 런타임은 넣지 않는다 |
| clip | `BROADCAST_INTAKE_ENABLED` · `BROADCAST_QUEUE_URL` | `app_env` | `false` · 빈 값 | 켜면 URL 필수. 큐는 `broadcast_queue_arn` 과 같은 것 |
| clip | `BROADCAST_REAPER_ENABLED` · `_INTERVAL` · `_GRACE` | `app_env`(선택) | `false` · `PT1M` · `PT30M` | 켜는 것은 운영자 결정. 켰는데 `stream_segments` 표가 없으면 부팅 거부 |
| clip | `RENDER_ENABLED` · `RENDER_QUEUE_URL` · `RENDER_DLQ_URL` · `CLIPS_BUCKET` · `SEGMENT_BUCKET` | `app_env` | `false` · 빈 값 | 켜면 넷 필수. 큐 · 버킷은 「작업 역할 대상」 의 같은 이름 ARN 과 짝 |
| clip | `UPLOAD_ENABLED` · `UPLOAD_QUEUE_URL` · `UPLOAD_DLQ_URL` | `app_env` | `false` · 빈 값 | 켜면 둘 필수 |
| clip | `THUMBNAIL_ENABLED` · `THUMBNAIL_QUEUE_URL` | `app_env` | `false` · 빈 값 | 켜면 URL 필수 |
| clip | `RENDER_RECONCILE_INTERVAL` · `UPLOAD_RECONCILE_INTERVAL` · `THUMBNAIL_INTERVAL` · `PLAYBACK_ACCESS_TTL` | `app_env`(선택) | `PT1M` · `PT1M` · `PT1M` · `PT60M` | |
| clip | `COLLECTOR_BASE_URL` | platform | 빈 값 | 값 = auth 와 같은 `http://chat-collector.pokeclip.internal:8083`. 비어도 부팅하지만 채팅 문 셋이 503 이다 |
| auth · clip | `AWS_REGION` | 넣지 않아도 됨 | `ap-northeast-2` | 기본값이 prod 리전과 같다 |
| auth · clip | `*_ENDPOINT` · `PROFILE_PHOTO_S3_FORCE_PATH_STYLE` | 넣지 않음 | 빈 값 · `false` | 로컬 호환 스토리지 전용. 비면 실제 AWS |
| next | (없음) | 넣지 않음 | — | 서버 주소 · `NEXT_PUBLIC_*` 는 이미지 빌드 인자로 굳는다(`web/Dockerfile`). `MEDIA_PLAYBACK_ORIGIN` 을 넣으면 로컬 전용 녹화 전달 라우트가 열린다 |

*   `*_ENDPOINT` 는 `PROFILE_PHOTO_S3_ENDPOINT` · `BROADCAST_QUEUE_ENDPOINT` ·
    `RENDER_QUEUE_ENDPOINT` 입니다.
*   `app_env` 에 넣지 않는 이름 · 값: 값 하나에 여러 속성을 싣는 이름
    (`SPRING_APPLICATION_JSON` · `JAVA_TOOL_OPTIONS` · `JDK_JAVA_OPTIONS` ·
    `JAVA_OPTS`)과 URL 자격 증명 · 쿼리 `password=` · 비밀 `-D` 속성이 든
    값은 platform plan 이 멈춥니다(검사 범위는 위 「앱이 읽는 env 이름」
    절). 쿼리 · 프래그먼트에 `@` 가 든 URL 도 걸릴 수 있으므로(알려진 오탐)
    그런 값은 `app_secret_arns` 로 넣습니다.
*   표에 없는 이름은 미확인입니다. 위 파일 밖(Java 코드의 직접 env 조회 등)은
    `services/auth` · `services/clip` 의 `src/main` 에서
    `getenv` · `@Value` 검색으로 더 찾지 못했습니다.

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
