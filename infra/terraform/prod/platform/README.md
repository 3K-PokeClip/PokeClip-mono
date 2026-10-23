# prod/platform

prod 의 요청 입구와 앱 서비스를 만드는 루트입니다(설계 3-1 의 ⑧).

*   **공개 ALB**(`pokeclip-prod-public`): `app.pokeclip.com` → next,
    `api.pokeclip.com` → auth · clip-sse · clip. REGIONAL WAF 를 붙입니다.
*   **내부 ALB**(`pokeclip-prod-internal`): CloudFront VPC origin 뒤에서
    `/live/*` · `/api/streams/*/rewind` · `/api/ingest/*` · `/playback/*` 를
    Media 대상 그룹 셋(live · control · playback)으로 보냅니다.
*   **SRT NLB**: Media AZ 퍼블릭 서브넷 하나에 두고, UDP 8890 은 팀 IP 만
    받습니다.
*   **ECS**: 클러스터 `pokeclip-prod` · Fargate arm64 서비스 셋(next · auth ·
    clip) · Cloud Map 네임스페이스 `pokeclip.internal`(`auth` · `clip` ·
    `next`).
*   **SG 규칙**: 공개 ALB 인그레스 · 이그레스, 서비스 인그레스(clip ↔ auth
    두 방향 포함), RDS 인그레스 ← auth · clip(설계 5-5).

두 ALB 리스너(HTTPS 443 · 서울 인증서)의 기본 동작은 고정 404 입니다. 규칙에
걸린 요청만 대상에 닿습니다 — api 호스트의 규칙 밖 경로와 모르는 Host 는
404 입니다.

이 루트에 **없는** 것은 다음과 같습니다(만드는 루트).

| 자원 | 루트 |
|---|---|
| 내부 ALB 인그레스 ← CloudFront VPC origin | edge |
| 내부 ALB · NLB 이그레스 → Media · Media 대상 등록 · Media ↔ RDS | media |
| Media ↔ auth · chat ↔ auth · clip 규칙(auth → chat-collector 8083 포함) | 런타임 2단(POK-306) |
| 일꾼 · chat 서비스 | POK-408 · POK-306(출력 `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids` 를 씀) |

용어:

*   **Cloud Map**: VPC 안 사설 DNS 로 서비스 이름(`auth.pokeclip.internal`)을
    태스크 IP 로 풀어 주는 AWS 서비스입니다.
*   **작업 역할(task role)**: 앱 코드가 AWS 를 부를 때 쓰는 IAM 역할입니다.
    **실행 역할(execution role)** 은 ECS 가 이미지를 끌어오고 비밀을 읽을 때
    씁니다.

## 공개 ALB 규칙

| 우선순위 | 호스트 | 경로 | 대상 그룹 |
|---|---|---|---|
| 100–108 | api | auth 접두사 아홉 × {`/api/x`, `/api/x/*`} — 접두사마다 규칙 하나 | auth |
| 200 | api | `/api/clip/broadcasts/*/events` | clip-sse |
| 300 | api | `/api/clip/*` | clip |
| 400 | app | (없음) | next |

auth 접두사는 `web/next.config.ts` 에서 `AUTH_API_URL` 로 가는 줄과 같습니다
(`/api/auth` · `/api/stream-keys` · `/api/chzzk-link` ·
`/api/editor-delegations` · `/api/editor-invitations` · `/api/youtube-link` ·
`/api/soop-link` · `/api/streamers` · `/api/profile-photos`). 그 파일에 auth
줄이 늘면 `load_balancers.tf` 의 `auth_path_prefixes` 도 함께 늘립니다.
ALB 규칙 하나에는 조건 값이 다섯까지라(계획 P1) 접두사 하나가 규칙 하나입니다.

clip-sse 는 같은 clip 서비스입니다. 긴 SSE 연결이 clip 그룹의 응답 시간
지표를 흐리지 않게 대상 그룹만 뗍니다.

브라우저는 api 를 부르지 않습니다(ADR-089 ③ — 브라우저 API 는 app 같은
오리진 → Next rewrite). 그래서 CORS 는 실제로 쓰이지 않지만, auth · clip 은
허용 출처가 비면 부팅하지 않습니다(`web-support` `CorsProperties` 의
`@NotEmpty`). 계획(「CORS env 를 넣지 않는다」)과 달리
`CORS_ALLOWED_ORIGINS = https://app.pokeclip.com` 하나만 둡니다(kty 확정 —
변수 `cors_allowed_origins`).

## Next 이미지 빌드 인자

Next 는 rewrite 목적지를 **빌드 때** 굳힙니다(web README 「컨테이너
이미지」). prod 이미지는 아래 값으로 빌드합니다 — 이 루트가 만드는 Cloud
Map 이름과 서비스 포트가 계약입니다.

| 빌드 인자 | 값 |
|---|---|
| `AUTH_API_URL` | `http://auth.pokeclip.internal:8082` |
| `CLIP_API_URL` | `http://clip.pokeclip.internal:8081` |

이름(`auth` · `clip` · `pokeclip.internal`)이나 `services` 의 포트를 바꾸면
next 이미지를 다시 빌드해야 합니다.

## 앱 env · 비밀

런타임 1단의 `app_env` · `app_secret_arns`(서비스 → 이름 → 값 · ARN)를
그대로 넣고, 아래 이름은 이 루트가 정합니다. 같은 이름이 `app_env` 에 있으면
이 루트 값이 이깁니다.

| 서비스 | 이름 | 값 |
|---|---|---|
| auth | `FORWARD_HEADERS_STRATEGY` | `native`(로드밸런서 뒤) |
| auth | `FORWARD_HEADERS_TRUSTED_PROXIES` | network `vpc_cidr`(ALB · next 가 VPC 안 — 빈 값이면 auth 가 아무 프록시도 안 믿는다) |
| auth | `STREAM_KEY_SECRET_STORE_TYPE` | `aws`(prod 는 처음부터 Secrets Manager — 앱 기본값 postgres) |
| auth | `STREAM_KEY_SECRET_RETIRE_FROM` | `aws`(PG 이관이 없어 옛 암호도 Secrets Manager 에만 있다) |
| auth | `STREAM_KEY_SECRET_REF_PREFIX` | `stream_key_secret_prefix`(작업 역할 정책과 같은 값) |
| clip | `CLOUDFRONT_KEY_PAIR_ID` | secrets `public_key_id` |
| clip | `MEDIA_BASE_URL` · `PLAYBACK_COOKIE_DOMAIN` | `https://media.pokeclip.com` · `.pokeclip.com` |
| auth | `CLIP_BASE_URL` · `COLLECTOR_BASE_URL` | `http://clip.pokeclip.internal:8081` · `http://chat-collector.pokeclip.internal:8083`(탈퇴 정리 — 비면 부팅 거부. chat-collector 등록은 POK-306) |
| auth · clip | `CORS_ALLOWED_ORIGINS` | `cors_allowed_origins`(기본 `https://app.pokeclip.com` — 위 「공개 ALB 규칙」) |
| clip | `AUTH_BASE_URL` | `http://auth.pokeclip.internal:8082`(clip → auth 자격 판정) |
| clip | `COLLECTOR_BASE_URL` | auth 와 같은 값(비어도 부팅은 되지만 채팅 문 셋이 503) |
| clip | `PLAYBACK_REQUIRED` | `true`(서명 값 셋 중 하나라도 빠지면 부팅 거부 — POK-321) |
| clip | `CLOUDFRONT_PRIVATE_KEY_PEM`(비밀) | secrets `signing_key_secret_arn` |
| auth · clip | `POSTGRES_USER` | 런타임 `app_db_users` 의 자기 사용자 |
| auth · clip | `POSTGRES_AUTH` · `POSTGRES_SSLROOTCERT` | `iam` · `postgres_sslrootcert` — `app_db_auth = "iam"` 일 때만 |

비밀은 ARN 만 받습니다. 실행 역할의 비밀 읽기 권한은 그 ARN 집합으로
한정됩니다(`ecs_service` 모듈).

## 작업 역할

코드가 실제로 부르는 AWS 동작만 줍니다(결정 135). 대상 ARN 은 런타임 1단
출력 `app_task_resource_arns` 에서 받고, 키가 없으면(기능 꺼짐) 그 문장을
만들지 않습니다. 근거는 `services/` 의 develop 코드입니다.

| 서비스 | 문장 | 동작 | 대상 | 근거 |
|---|---|---|---|---|
| auth | `StreamKeySecrets` | `secretsmanager:CreateSecret` · `PutSecretValue` · `GetSecretValue` · `DeleteSecret` | `secret:<stream_key_secret_prefix>*`(이름 = 접두 + UUID) | `SecretsManagerSecretStore.java:86` · `93` · `113` · `140` |
| auth | `ProfilePhotoObjects` | `s3:PutObject` · `GetObject` · `DeleteObject` | `profile_photo_bucket_arn/*` | `S3PhotoStorage.java:38` · `66` · `94` |
| clip | `BroadcastIntake` | `sqs:ReceiveMessage` · `DeleteMessage` | `broadcast_queue_arn` | `SqsIntakeRunner.java:212` · `427` |
| clip | `OrderQueues` | `sqs:SendMessage` | `render_queue_arn` · `upload_queue_arn` · `thumbnail_queue_arn` | `RenderQueueClient.java:30` · `UploadQueueClient.java:29` · `ThumbnailQueueClient.java:18` |
| clip | `DeadLetterQueues` | `sqs:ReceiveMessage` · `DeleteMessage` | `render_dlq_arn` · `upload_dlq_arn` | `RenderQueueClient.java:36` · `41` · `UploadQueueClient.java:34` · `39` |
| clip | `ClipObjects` | `s3:GetObject` · `PutObject` · `DeleteObject` | `clips_bucket_arn/*` | 서명 URL `ClipFileSigner.java:42` · `ThumbnailSigner.java:24` · `UploadThumbnailStore.java:69` · `82` · `S3PurgeStorage.java:57` |
| clip | `ClipList` | `s3:ListBucket` | `clips_bucket_arn` | `S3PurgeStorage.java:36` |
| clip | `SegmentDelete` | `s3:DeleteObject` | `segment_bucket_arn/*` | `S3PurgeStorage.java:57` |
| auth · clip | `DbConnect` | `rds-db:connect` | `dbuser:<rds_resource_id>/<자기 사용자>` — `app_db_auth = "iam"` 일 때만 | 공통 지시 6(POK-407) |

*   서명 URL 은 작업 역할 자격으로 서명되므로, 받는 사람이 그 URL 로 읽으려면
    역할에 `s3:GetObject` 가 있어야 합니다.
*   맨 `*` Resource 는 없습니다(`ecs_service` 검증). 런타임 출력 값은
    `terraform_data.runtime_contract` 가 먼저 봅니다 — 와일드카드 · 모르는 키 ·
    DB 사용자 이름 꼴이 틀리면 plan 이 멈춥니다.
*   같은 자원이 앱의 부팅 거부 조건도 미리 봅니다 — `app_db_auth = "iam"` 인데
    auth · clip 에 `POSTGRES_PASSWORD` 가 오거나, auth 에
    `STREAM_KEY_RESOLVE_TOKEN` 이 오는데 `STREAM_KEY_RESOLVE_ALLOWED_CIDRS` 가
    비면 plan 이 멈춥니다.
*   next 는 AWS 를 부르지 않아 작업 역할에 정책이 없습니다.

## 값과 적용

`image_digests` · `proxy_ports` · `media_health_path` · `ingest_allowed_cidrs`
는 기본값이 없습니다. `terraform.tfvars.example` 을
`terraform.tfvars`(추적 안 함)로 복사해 채웁니다. `proxy_ports` ·
`media_health_path` 는 Media 상자 nginx 포트 · 헬스 경로의 정본입니다 —
대상 그룹이 이 값을 쓰고, 같은 값을 출력(`media_proxy_ports` ·
`media_health_path`)으로 내보내 prod/media 가 remote_state 로 읽습니다.

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `public_alb_name` · `internal_alb_name` | `pokeclip-prod-public` · `pokeclip-prod-internal` | 대상 그룹 이름(`<ALB>-<키>`, 32자 상한)이 들어가게 짧게 둠 |
| `waf_managed_rule_groups` | KnownBadInputs · AmazonIpReputationList | CommonRuleSet 은 본문 8KB 규칙이 프로필 사진(최대 2MB) 업로드를 막아 뺌 |
| `waf_rate_limit` | 2000 | IP 하나당 5분 요청 수 |
| `services` | next 3000 `/api/ping` 512/1024 · 60초, auth 8082 · clip 8081 `/actuator/health` 1024/2048 · 180초 | 포트 · 헬스 경로 · CPU · 메모리(MiB) · 기동 유예(Flyway) |
| `app_db_auth` | `iam` | `password` 면 `rds-db:connect` · `POSTGRES_AUTH` 없음 |
| `postgres_sslrootcert` | `/etc/pokeclip/rds-ca/global-bundle.pem` | 서버 이미지의 RDS CA 번들 경로(POK-407 코멘트 10715 · 커밋 2975805e) — iam 에서만 넣음 |
| `cors_allowed_origins` | `https://app.pokeclip.com` | auth · clip 부팅 필수 값(실사용 없음) |
| `ingest_public_approved` | `false` | `ingest_allowed_cidrs` 에 /16 보다 넓은 CIDR(0.0.0.0/0 등)을 받을지 — S1 게이트 · 응답기(M15) 반영 뒤 8a 단계에서만 `true` |
| `stream_key_secret_prefix` | `pokeclip/prod/stream-key/` | auth 스트림 키 비밀 이름 접두 |

앞 루트 상태는 `state_bucket`(`pokeclip-tfstate-prod-apne2`) · `state_keys`
(network · certs · secrets · registry · runtime)로 읽습니다. 런타임 1단 키는
`prod/runtime/terraform.tfstate` 로 고정입니다(CONTRACT-runtime.md).

**O7d 선행 확인 — IAM DB 인증**: PR 343 으로 auth · clip(chat-collector
포함)도 db-support 의 `POSTGRES_AUTH` 를 읽습니다 — `app_db_auth` 는 기본
`iam` 을 유지합니다. iam 에서는 런타임이 DB 비밀번호 그릇
(`POSTGRES_PASSWORD`)을 주지 않습니다. 그 키가 있으면 앱이 부팅을 거부합니다.
`password` 로 바꿀 때만 런타임이 그 그릇을 `app_secret_arns` 로 줍니다.

적용은 O7d(kty 승인)입니다. 그 전에 [단계 직전 점검](/infra/terraform/runbooks/stage-precheck.md)
과 [ALB 교차 영역 실측](/infra/terraform/runbooks/alb-cross-zone-bench.md)을
하고, 런타임 1단 출력이 있는지 확인합니다. 백엔드는 `backend.hcl.example` 을
`backend.hcl` 로 복사하고 `bucket` 자리표시자 `<STATE_BUCKET_PROD>` 를
`pokeclip-tfstate-prod-apne2` 로 고친 뒤 `init -backend-config=backend.hcl`
로 엽니다(운영 단계).

provider 의 `default_tags` 네 키(`TerraformRoot = prod/platform`)는 태그를
받는 자원에 붙습니다. mock 시험은 태그를 보지 못하므로 plan 의 `tags_all` 로
확인합니다.

### apply 뒤 되돌리기

apply 한 뒤 이 루트를 내리거나 코드를 되돌릴(revert) 때는
[prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md)을 따릅니다.
**revert 전에** 내립니다 — 되돌리면 이 루트의 코드가 사라져 destroy 할
구성이 없어지고, 자원이 관리 밖에 남습니다. 이 루트의 출력을 읽는
prod/media 를 먼저 내리고(이 루트를 먼저 내리면 media 의 destroy plan 이
출력 이름으로 실패합니다), edge · records · 런타임 2단 같은 다른 소비
루트가 apply 돼 있으면 그 루트도 먼저입니다(아래 「출력」). destroy 마다
plan 요지를 kty 가 보고 승인합니다.

## 출력

소비 루트와의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `public_alb_{arn,dns_name,zone_id,sg_id}` | records(app · api 별칭) |
| `internal_alb_{arn,dns_name,sg_id}` | edge(VPC origin · 인그레스) · media(이그레스 → Media) |
| `nlb_{dns_name,zone_id,sg_id}` | records(ingest 별칭) · media(이그레스 → Media) |
| `target_group_arns`(ingest · live · control · playback) | media — Media 상자 등록 |
| `media_proxy_ports`(live · control · playback · health) · `media_health_path` | media — 상자 nginx 포트 · 헬스 경로(대상 그룹과 같은 값) |
| `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids`(auth · clip · next) | 런타임 2단 · 일꾼(POK-408) · chat(POK-306) |
| `clip_execution_role_arn` | 런타임 2단 |
