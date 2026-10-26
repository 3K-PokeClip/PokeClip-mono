# prod/platform

prod 의 요청 입구와 앱 서비스를 만드는 루트입니다(설계 3-1 의 ⑧).

*   **공개 ALB**(`pokeclip-prod-public`): `app.pokeclip.com` → next,
    `api.pokeclip.com` → auth · clip-sse · clip. REGIONAL WAF 를 붙입니다.
*   **내부 ALB**(`pokeclip-prod-internal`): CloudFront VPC origin 뒤에서
    `/live/*` · `/api/streams/*/rewind` · `/api/ingest/*` · `/playback/*` 를
    Media 대상 그룹 셋(live · control · playback)으로 보냅니다.
*   **SRT NLB**: Media AZ 퍼블릭 서브넷 하나에 두고, UDP 8890 은 팀 IP 만
    받습니다.
*   **ECS**: 클러스터 `pokeclip-prod` · Fargate arm64 서비스 다섯(next ·
    auth · clip · chat-collector · chat-detector) · Cloud Map 네임스페이스
    `pokeclip.internal`(서비스 이름 그대로). chat 두 서버는 공개 ALB · 대상
    그룹 없이 Cloud Map 이름으로만 받습니다(아래 「chat 서비스」).
*   **SG 규칙**: 공개 ALB 인그레스 · 이그레스, 서비스 인그레스(clip ↔ auth
    두 방향 · chat ↔ auth · clip 포함), RDS 인그레스 ← auth · clip ·
    chat-collector · chat-detector(설계 5-5).

두 ALB 리스너(HTTPS 443 · 서울 인증서)의 기본 동작은 고정 404 입니다. 규칙에
걸린 요청만 대상에 닿습니다 — api 호스트의 규칙 밖 경로와 모르는 Host 는
404 입니다.

이 루트에 **없는** 것은 다음과 같습니다(만드는 루트).

| 자원 | 루트 |
|---|---|
| 내부 ALB 인그레스 ← CloudFront VPC origin | edge |
| 내부 ALB · NLB 이그레스 → Media · Media 대상 등록 · Media ↔ RDS · Media ↔ auth(출력 `service_sg_ids.auth` · `service_ports.auth` 를 씀 — kty 결정 2026-10-25 (나)) | media |
| 일꾼 서비스 | POK-408(출력 `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids` 를 씀) |

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

## 공개 WAF 데이터 보호

공개 ALB 의 REGIONAL WAF 는 웹 ACL 데이터 보호로 아래 값을 요청 샘플 · 로그
모두에서 `REDACTED` 로 바꿉니다(`load_balancers.tf` 의 `module "waf"`).

*   **헤더**: `authorization` · `cookie` · `referer` · `x-internal-token` ·
    `x-stream-key-resolve-token`. 앞의 둘은 로그 가림(`redacted_fields`)도
    겹쳐 둡니다.
*   **질의 인자**: `code`(OAuth 콜백) · `token`(프로필 사진 주소).

이 설정에는 운영 대가가 둘 있습니다. 차단 동작은 바뀌지 않습니다 — 데이터
보호는 저장되는 요청 데이터만 바꿉니다.

*   **질의 문자열 전체가 일치 세부에서 가려집니다.** 질의 인자를 하나라도
    가리면 로그의 `RuleMatchDetails` · `rateBasedRuleList` 에서는 질의 문자열
    전체가 `REDACTED` 입니다(AWS WAF 개발자 안내서 「Data protection
    limitations」). 그래서 질의를 보는 규칙(KnownBadInputs 의
    `*_QUERYSTRING` 규칙 등)에 걸린 차단 로그에서 공격 페이로드를 볼 수
    없습니다.
*   **출처를 잃습니다.** `referer` 를 가리므로 요청이 어느 화면에서 왔는지
    WAF 기록으로는 알 수 없습니다.

오탐을 볼 때는 가려지지 않는 자리를 씁니다.

*   어느 규칙이 걸렸는지: 로그의 `terminatingRuleId` · `ruleGroupList` 의
    규칙 이름과 `conditionType` · `location` · `labels`.
*   요청 대상: `httpRequest.uri` · `httpRequest.args`. `args` 에서는 가린
    인자(`code` · `token`)만 `REDACTED` 이고 나머지 인자는 원문입니다(같은
    안내서 「Examples of data protection」).
*   요청 샘플(최근 3시간)에도 같은 보호가 걸립니다. 샘플이 로그보다 더
    보여 주지는 않습니다.

질의 원문이 꼭 필요하면 데이터 보호 예외(`exclude_rule_match_details`)를
잠시 켜야 합니다. `modules/waf_acl` 에 그 입력이 없으므로 코드 변경과 리뷰를
거칩니다.

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

## chat 서비스

chat-collector(채팅 수집 · 8083)와 chat-detector(하이라이트 판별 · 8084)는
auth · clip 과 같은 `ecs_service` 모듈로 둡니다(POK-306). 바깥에서 부르는
쪽이 없어 공개 ALB · 대상 그룹이 없고, 그래서 next · auth · clip 의 태스크
정의 · 대상 그룹 · 공개 ALB 규칙은 chat 이 생겨도 바뀌지 않습니다
(`tests/chat_services.tftest.hcl` 이 값으로 못 박음). 컨테이너 헬스체크 ·
배포 비율은 auth · clip 처럼 두지 않습니다(ECS 기본값).

| 부르는 쪽 → 받는 쪽 | 포트 | 왜 |
|---|---|---|
| auth → chat-collector | 8083 | 탈퇴 정리(`COLLECTOR_BASE_URL`) |
| clip → chat-collector | 8083 | 채팅 문 셋(`COLLECTOR_BASE_URL`) |
| chat-detector → chat-collector | 8083 | 채팅 읽기(`COLLECTOR_BASE_URL`) |
| chat-collector → auth | 8082 | 방송 편지의 열쇠 조회 · 링크(`AUTH_BASE_URL`) |
| chat-collector → clip | 8081 | 재부착 · 중계 · 방송 정보(`CLIP_BASE_URL`) |
| chat-detector → clip | 8081 | 하이라이트 카드(`CLIP_BASE_URL`) |
| chat 둘 → RDS | 5432 | 자기 스키마(IAM DB 인증 · verify-full) |

치지직 Open API · 채팅 소켓 · SOOP API · SDK · SQS · 채팅 원본 보관 창고(S3)는
공통 이그레스(443 → NAT · S3 게이트웨이 엔드포인트)로 나갑니다. SOOP 채팅
소켓은 443 밖일 수 있습니다(아래 「chat 서비스의 한계」).

**chat 재배포는 수집 중인 방송을 잠시 끊습니다.** 시작 편지는 일회용이라 새
태스크는 재부착(`chat_collector_features.reattach` — 기본 켬)으로 진행 중
방송에 다시 붙습니다. 옛 태스크가 내려간 뒤 다시 붙기 전까지의 채팅은 받지
못합니다(첫 회차 5초 뒤 · 그 뒤 1분마다). 재부착을 끈 배포는 방송이 없는
시간에 합니다. 다이제스트 · env · 크기를 바꾸는 apply 도 재배포입니다.

### chat 기능 스위치

`chat_collector_features` 가 chat-collector 의 스위치 여섯을 env 로
넣습니다(서버 기본값은 모두 꺼짐).

| 키 | env | 기본값 | 뜻 |
|---|---|---|---|
| `reattach` | `CHAT_REATTACH_ENABLED` | `true` | 재배포 · 재시작으로 잃은 방송 다시 붙기 |
| `relay` | `CHAT_RELAY_ENABLED` | `true` | 받은 채팅을 clip 에 바로 밀기(SSE) |
| `liveinfo` | `BROADCAST_INFO_ENABLED` | `true` | 방송 정보(제목 · 시청자 수) 1분마다 |
| `soop_watch` | `SOOP_WATCH_ENABLED` | `true` | SOOP 방송 지켜보기 |
| `soop_chat` | `SOOP_CHAT_ENABLED` | `true` | SOOP 채팅 받기(Chromium 탭) |
| `detach` | `CHAT_DETACH_ENABLED` | `false` | clip 명부에 없는 방송의 세션 반납 |

*   기본값은 kty 결정(2026-10-26)입니다. 떼기는 clip 치우개
    (`BROADCAST_REAPER_*`)와 짝이라 O7e 뒤 운영자가 함께 정합니다.
*   부팅 거부 조합은 plan 이 막습니다 — `detach` 는 `reattach` 와,
    `soop_chat` 은 `soop_watch` 와 함께만 켭니다. 재부착이 요구하는 방송
    편지 받기(`BROADCAST_INTAKE_ENABLED`)는 이 루트가 늘 켭니다.
*   방송 정보 · SOOP 채팅의 앱 값(`CHZZK_CLIENT_ID` · `CHZZK_CLIENT_SECRET` ·
    `SOOP_CLIENT_ID` · `SOOP_CLIENT_SECRET`)은 런타임이 줍니다. 켰는데 비면
    서버가 부팅을 거부합니다.

### chat 서비스의 한계

*   **SOOP 채팅 소켓은 443 밖일 수 있습니다.** 공식 Chat SDK 를 정적으로
    읽으면 WebSocket 주소가 `chat-<hex>.sooplive.com:<채팅 포트 + 1>` 꼴입니다.
    collector 이그레스는 443(과 내부 짝)뿐이라 prod 에서 SOOP 채팅만 붙지
    못할 수 있습니다. 배포 뒤 VPC 흐름 로그로 목적지 포트를 실측한 뒤
    이그레스를 더합니다(kty 2026-10-26 · POK-306 Q8). 치지직 · SOOP 지켜보기 ·
    방송 정보는 443 이라 영향이 없습니다.
*   판별 서버가 수집 서버의 Flyway(판별 계정에 `chat_messages` 읽기 권한을
    줌)보다 먼저 떠도 되는지는 3번 답(POK-306 Q5) 전까지 모릅니다. 이 루트는
    두 모듈 사이에 순서를 두지 않습니다(`services.tf` 의 `module
    "chat_detector"` 주석).

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
| auth | `CLIP_BASE_URL` · `COLLECTOR_BASE_URL` | `http://clip.pokeclip.internal:8081` · `http://chat-collector.pokeclip.internal:<chat_services 포트>`(탈퇴 정리 — 비면 부팅 거부) |
| auth · clip | `CORS_ALLOWED_ORIGINS` | `cors_allowed_origins`(기본 `https://app.pokeclip.com` — 위 「공개 ALB 규칙」) |
| clip | `AUTH_BASE_URL` | `http://auth.pokeclip.internal:8082`(clip → auth 자격 판정) |
| clip | `COLLECTOR_BASE_URL` | auth 와 같은 값(비어도 부팅은 되지만 채팅 문 셋이 503) |
| clip | `PLAYBACK_REQUIRED` | `true`(서명 값 셋 중 하나라도 빠지면 부팅 거부 — POK-321) |
| clip | `CLOUDFRONT_PRIVATE_KEY_PEM`(비밀) | secrets `signing_key_secret_arn` |
| chat-collector | `BROADCAST_INTAKE_ENABLED` | `true`(방송 편지로 세션을 연다 — 운영의 시작 경로) |
| chat-collector | `AUTH_BASE_URL` · `CLIP_BASE_URL` | clip 과 같은 auth 주소 · auth 와 같은 clip 주소 |
| chat-collector | 기능 스위치 여섯 | 위 「chat 기능 스위치」 |
| chat-detector | `CLIP_BASE_URL` · `COLLECTOR_BASE_URL` | auth 와 같은 값 |
| auth · clip · chat 둘 | `POSTGRES_USER` | 런타임 `app_db_users` 의 자기 사용자 |
| auth · clip · chat 둘 | `POSTGRES_AUTH` · `POSTGRES_SSLROOTCERT` | `iam` · `postgres_sslrootcert` — `app_db_auth = "iam"` 일 때만 |

채팅 원본 보관 창고 이름 `S3_BUCKET` 은 런타임 `app_env` 가 줍니다(작업
역할의 창고와 같아야 함 — 아래 「작업 역할」). chat-collector 에 넣지 않는
것: `CHZZK_ENABLED`(옛 한 채널 경로 — 받기와 같이 켜면 부팅 거부) ·
`CHAT_SYNC_OFFSET_MS`(서버 기본 3900). chat-detector 의 `DETECTION_*` 은
서버 기본값입니다.

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
| clip | `SegmentRead` | `s3:GetObject` | `segment_bucket_arn/*` | 서명 URL `SegmentAudioSigner.java:33`(트랙별 미리 듣기, POK-389) |
| chat-collector | `ChatBroadcastIntake` | `sqs:ReceiveMessage` · `DeleteMessage` · `GetQueueAttributes` | 런타임 `broadcast_queue_arns.chat` | `SqsIntakeRunner.java:254` · `457` · `152`(부팅 때 가시성 시한 관측) |
| chat-collector | `ChatArchiveObjects` | `s3:PutObject` · `DeleteObject` | 런타임 `chat_archive_bucket_arn` 의 `chat/*` | `S3ArchiveUploader.java:22` · `S3ArchivePurge.java:84`(묶음 지우기 — 객체마다 `DeleteObject`) |
| chat-collector | `ChatArchiveList` | `s3:ListBucket` | `chat_archive_bucket_arn`(버킷) | `S3ArchivePurge.java:50`(채널 접두 훑기) |
| auth · clip · chat 둘 | `DbConnect` | `rds-db:connect` | `dbuser:<rds_resource_id>/<자기 사용자>` — `app_db_auth = "iam"` 일 때만 | 공통 지시 6(POK-407) |

*   서명 URL 은 작업 역할 자격으로 서명되므로, 받는 사람이 그 URL 로 읽으려면
    역할에 `s3:GetObject` 가 있어야 합니다.
*   맨 `*` Resource 는 없습니다(`ecs_service` 검증). 런타임 출력 값은
    `terraform_data.runtime_contract` 가 먼저 봅니다 — 와일드카드 · 모르는 키 ·
    DB 사용자 이름 꼴이 틀리면 plan 이 멈춥니다.
*   chat 큐 ARN 은 와일드카드 없는 FIFO 큐(`.fifo`)여야 합니다. 보관 창고
    ARN 은 와일드카드 없는 버킷 ARN 이고, 그 버킷 이름이 chat-collector 의
    `S3_BUCKET` 과 같아야 합니다 — 아니면 plan 이 멈춥니다.
*   `ChatArchiveList` 는 버킷 전체 목록입니다. `ecs_service` 의 문장에 조건
    자리가 없어 `s3:prefix = chat/` 로 좁히지 않습니다 — 창고가 chat 전용이라
    목록이 넓어져도 보이는 것은 채팅 원본뿐입니다.
*   같은 자원이 앱의 부팅 거부 조건도 미리 봅니다 — `app_db_auth = "iam"` 인데
    DB 를 쓰는 서버 넷(auth · clip · chat 둘)에 `POSTGRES_PASSWORD` 가 오거나, auth 에
    `STREAM_KEY_RESOLVE_TOKEN` 이 오는데 `STREAM_KEY_RESOLVE_ALLOWED_CIDRS` 가
    비면 plan 이 멈춥니다.
*   런타임 `app_env` 는 평문 env 라 비밀은 `app_secret_arns` 로 와야 합니다.
    이름 어디에든 `PASSWORD` · `SECRET` · `TOKEN` · `KEY` 등이 들면 plan 이
    멈춥니다 — 비밀이 아닌 그런 이름은 `app_env_non_secret_names` 에 적습니다.
    이 목록으로도 풀리지 않는 검사가 넷 있습니다 — 이름 꼴(대문자 · 숫자 ·
    `_`), 값 운반 이름(`SPRING_APPLICATION_JSON` · `JAVA_TOOL_OPTIONS` ·
    `JDK_JAVA_OPTIONS` · `JAVA_OPTS`), URL 자격 증명(`user:pass@` ·
    `<토큰>@`), 값에 비밀을 싣는 꼴(키에 `passw` · `pwd` · `secret` · `token`
    이 든 쿼리 · 프래그먼트 매개변수 · 이름에 `secret` · `password` · `token`
    이 든 `-D` 속성). 값 검사는 이 낱말만 봐서 `?api_key=` · `-Ddb.passwd=` 는
    통과합니다 — 비밀은 검사에 기대지 말고 `app_secret_arns` 로 넘깁니다. 쿼리 ·
    프래그먼트 속 `@` 도 자격 증명으로 보고 멈출 수 있습니다(알려진 오탐) — 그런 값도
    `app_secret_arns` 로 옮깁니다.
*   next 는 AWS 를 부르지 않아 작업 역할에 정책이 없습니다. chat-detector
    는 DB 접속(`DbConnect`)뿐입니다.

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
| `services` | next 3000 `/api/ping` 512/1024 · 60초, auth 8082 · clip 8081 `/actuator/health` 1024/2048 · 180초 | 포트 · 헬스 경로 · CPU · 메모리(MiB) · 기동 유예(Flyway). 공개 ALB 대상 그룹 · 규칙의 원천이라 키는 이 셋뿐 |
| `chat_services` | chat-collector 8083 · 1024/2048, chat-detector 8084 · 512/1024 | 포트(서버 `server.port`) · CPU · 메모리(MiB). 키는 이 둘뿐 |
| `chat_collector_features` | 위 「chat 기능 스위치」 | chat-collector 기능 스위치 여섯 |
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
| `ecs_cluster_arn` · `discovery_namespace_id` · `service_sg_ids`(auth · clip · next) | 런타임 2단 · 일꾼(POK-408) · media(`service_sg_ids.auth` — Media ↔ auth). chat 두 서비스의 SG 는 이 루트 안에서만 써서 내보내지 않음 |
| `service_ports`(auth · clip · next — `services` 의 포트) | media — Media ↔ auth 규칙의 포트(kty 결정 2026-10-25 (나)) |
| `clip_execution_role_arn` | 런타임 2단 |
