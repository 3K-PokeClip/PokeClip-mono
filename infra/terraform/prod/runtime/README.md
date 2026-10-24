# prod/runtime

prod 런타임 1단(설계 3-1 의 ⑤)을 만드는 루트입니다 — RDS PostgreSQL 17 ·
앱 비밀 그릇 아홉 · 계약9 SNS FIFO 주제와 SQS FIFO 큐 · rewind 버킷 · SQL
실행기 스위치(1단-가), 일 큐 셋과 DLQ · clips · 프로필 사진 버킷 · auth 설정
(OAuth · 사진 주소) · clip 일 스위치(1단-다). platform · media · edge · 일꾼
배치 루트는 이 루트의 상태(키 `prod/runtime/terraform.tfstate`)를
`terraform_remote_state` 로 읽습니다.

출력 이름과 소유 경계의 정본은
[런타임 트랙 인터페이스 계약](../../CONTRACT-runtime.md)입니다. 이 루트가
만들지 않는 것은 다음과 같습니다.

*   SG 규칙(RDS ← SQL 실행기 5432 규칙만 예외 — 실행기가 켜진 동안), 비밀
    읽기 · DB 접속 · 발행 · 수신 정책. 소비 루트가 자기 역할에 붙입니다.
*   rewind 버킷 정책(OAC 허용문). edge 배포 ARN 이 있어야 해서 런타임 2단이
    만듭니다.
*   media DB 계정 · 앱 서버 DB 사용자 · `stream_*` 스키마 · 권한. Terraform
    이 아니라 `sql/` 과 [runtime-1a 런북](../../runbooks/runtime-1a.md)으로
    적용합니다. 앱 서버 사용자 이름은 출력 `app_db_users` 가
    `sql/10_roles.sql` 에서 읽어 냅니다.
*   비밀 값. 그릇만 만들고 값은 운영 단계에서 Terraform 밖으로 넣습니다
    (guard R1 · R6). 마스터 비밀번호는 RDS 관리형 비밀이라 상태에 값이
    없습니다.

## 파일 배치

파일 하나가 티켓 하나입니다(병렬로 개발할 때 충돌을 없애려고 — 다른 루트의
`main.tf` 관례와 다른 이유). 출력은 그 파일 안에 둡니다. `variables.tf` 에는
루트 공통 변수(`region` · `network_state_*`)만 두고, 티켓 변수는 각 파일
머리에 둡니다. tflint 는 설정 파일이 없어 recommended 묶음으로 돌고, 표준
모듈 구조 규칙(`terraform_standard_module_structure`)은 그 묶음에 없습니다.

| 파일 | 담는 것 | 티켓 |
|---|---|---|
| `versions.tf` · `providers.tf` · `backend.tf` · `variables.tf` | 규약 · default_tags(`TerraformRoot = prod/runtime`) · 루트 공통 변수 | POK-304 |
| `network_state.tf` | prod/network 원격 상태 → `vpc_id` · `private_subnet_ids` · `media_az` · `media_private_subnet_id` · Media 프라이빗 서브넷 CIDR 조회(`data "aws_subnet"`) | POK-304 · POK-429 |
| `rds.tf` | RDS 인스턴스 · 서브넷 그룹 · 파라미터 그룹 · 로그 그룹(30일) · RDS SG(규칙 0) | POK-304 |
| `app_secrets.tf` | 앱 비밀 그릇 아홉과 서비스별 ARN 지도(일꾼 render · upload 포함) · Media 가 읽는 그릇 | POK-305 · POK-427 · POK-408 · POK-429 |
| `rewind_bucket.tf` | rewind 버킷(공개 차단 · SSE-S3 · 미완료 업로드 정리 · 앱 출처 `app_origin` 의 GET · HEAD CORS) — segment 창고를 겸함 | POK-308 · POK-427 |
| `broadcast_events.tf` · `app_env.tf` | 계약9 주제 · 주제 정책 · 큐 · DLQ · 구독 · 큐 정책 · 서비스별 env · clip 일 스위치 변수 `clip_job_features` | POK-309 · POK-408 |
| `job_queues.tf` | 일 큐 셋(render · thumbnail · upload — 표준 SQS)과 DLQ · redrive | POK-408 |
| `clips_bucket.tf` | clips 버킷(공개 차단 · SSE-S3 · 만료 60일 · 미완료 업로드 정리) | POK-408 |
| `profile_photo_bucket.tf` | 프로필 사진 버킷(공개 차단 · SSE-S3 · 미완료 업로드 정리 · 만료 없음) | POK-427 |
| `auth_settings.tf` | auth 설정 변수 `auth_oauth_apps` · `profile_photo_base_url`(검증 = 앱 부팅 검사) | POK-427 |
| `app_task_resources.tf` | 작업 역할 대상 출력 `app_task_resource_arns` | POK-408 |
| `app_db_users.tf` | 앱 서버 DB 사용자 이름 출력(`sql/10_roles.sql` 에서 읽음) | POK-407 |
| `sql_runner.tf` | SQL 실행기 스위치(꺼짐이 기본) | POK-304 |
| `sql/` | media DB 계정 · 앱 서버 DB 사용자 · `stream_*` 스키마 · 권한 · 확인 · advisory 확인 SQL | POK-304 · POK-407 |
| `terraform.tfvars.example` | `auth_oauth_apps` 자리표시자 · 선택 변수 | POK-427 |
| `tests/` | 파일마다 mock 시험 · 공유 mock 기본값(`tests/mocks/`). 시험 파일마다 파일 단위 `variables` 블록이 `auth_oauth_apps` 예시 값을 준다(기본값 없는 변수) | — |

## 적용

절차 · 승인 지점은 [runtime-1a 런북](../../runbooks/runtime-1a.md)입니다
(1단-다는 그 런북의 「1단-다」 절). `terraform.tfvars` 가 필요합니다 —
`auth_oauth_apps` 는 기본값이 없습니다. `terraform.tfvars.example` 을
`terraform.tfvars` 로 복사해 kty 가 값을 넣고, 커밋하지 않습니다
(`infra/terraform/.gitignore`). 백엔드는 `backend.hcl.example` 을
`backend.hcl` 로 복사하고 `bucket` 자리표시자 `<STATE_BUCKET_PROD>` 를
`pokeclip-tfstate-prod-apne2` 로 고친 뒤 `init -backend-config=backend.hcl` 로
엽니다(운영 단계 · kty 승인).

provider 의 `default_tags` 네 키는 태그를 받는 자원에 붙습니다. mock 시험은
`tags_all` 을 보지 못하므로 plan 으로 확인합니다. RDS 관리형 마스터 비밀에
default_tags 가 붙는지는 미확인이고, 런북 「운영 확인」 에서 봅니다.

## 출력

소비 루트와의 계약입니다. 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `rds_sg_id` | platform — RDS ← auth · clip 규칙 · media — Media ↔ RDS 5432 규칙 |
| `rds_endpoint` | media 사이드카 · janitor 의 DB 주소 · `app_env` 의 `DB_HOST`(호스트만 — 포트는 5432) |
| `rds_db_name` | media · `app_env` 의 `POSTGRES_DB` |
| `rds_resource_id` | media — 인스턴스 역할 `rds-db:connect` 의 대상 |
| `rewind_bucket_name` · `rewind_bucket_arn` · `rewind_bucket_regional_domain_name` | edge — `rewind-s3` 오리진 · media — 업로드 정책 · 런타임 2단 — 버킷 정책 |
| `app_secret_arns`(서비스 → env 이름 → 전체 ARN) | platform — `ecs_service.secrets` · 실행 역할 읽기 정책. auth 아홉(`SOOP_CLIENT_SECRET` · resolve 전용 토큰 `STREAM_KEY_RESOLVE_TOKEN` 포함) · clip 둘(`JWT_SECRET` · `INTERNAL_API_TOKEN`) · chat-collector 둘(`INTERNAL_API_TOKEN` · `CHZZK_CLIENT_SECRET`) · chat-detector 하나(`INTERNAL_API_TOKEN`). 일꾼 render · upload 하나씩(`INTERNAL_API_TOKEN` — 앱과 같은 그릇)은 배치 루트가 읽는다 |
| `media_secret_arns`(비밀 전체 ARN 목록 — `list(string)`) | media — 인스턴스 역할 읽기 정책(media_host 변수 `media_secret_arns` 와 같은 꼴). 원소는 resolve 전용 토큰 그릇 하나(auth 의 `STREAM_KEY_RESOLVE_TOKEN` 과 같은 그릇) — 공용 `INTERNAL_API_TOKEN` 은 없다(POK-429) |
| `ingest_auth_resolve_token_secret_arn`(전체 ARN — string) | media(M15) — 응답기 `media.env` 의 `STREAM_KEY_RESOLVE_TOKEN_SECRET_ARN` 렌더. `media_secret_arns` 의 원소와 같은 값이고, 목록의 순서에 기대지 않으려는 이름 있는 출력이다 |
| `app_env`(서비스 → env 이름 → 값) | platform — `ecs_service.environment`. 네 서버 모두 `DB_HOST` · `DB_PORT` · `POSTGRES_DB`, auth 는 OAuth 네 덩어리의 `*_CLIENT_ID` · `*_REDIRECT_URI` 와 `PROFILE_PHOTO_S3_BUCKET` · `PROFILE_PHOTO_BASE_URL` · resolve 전용 창구의 허용 대역 `STREAM_KEY_RESOLVE_ALLOWED_CIDRS`(Media 프라이빗 서브넷 CIDR)(스트림 키 접두 `STREAM_KEY_SECRET_REF_PREFIX` 는 platform 이 넣는다), clip · chat-collector 는 자기 큐의 `BROADCAST_QUEUE_URL`, clip 은 수신 스위치 `BROADCAST_INTAKE_ENABLED = "true"`(문자열 — 빠지면 큐를 읽지 않아 편지가 보관 기간 뒤 사라진다) · 일 스위치 셋(`clip_job_features`) · 일 큐 주소 다섯 · `CLIPS_BUCKET` · `SEGMENT_BUCKET`(= rewind). `AUTH_BASE_URL` 은 platform 이 넣는다. chat-collector 의 스위치는 배치(POK-306)가 `AUTH_BASE_URL` · `INTERNAL_API_TOKEN` 과 함께 정한다. 일꾼 render · upload 의 큐 주소 · 숨김 env 는 배치 루트가 읽는다 |
| `broadcast_topic_arn` | media — 사이드카 역할의 `sns:Publish` 대상(신원 정책 — 주제 정책은 계정 루트만 주체로 둔다) |
| `broadcast_queue_arns`(소비자 clip · chat → 큐 ARN) | chat-collector(POK-306)의 수신 정책 대상. clip 은 `app_task_resource_arns` 를 쓴다 |
| `app_db_users`(서비스 → DB 사용자 이름) | platform — 태스크 env `POSTGRES_USER` · `rds-db:connect` 의 사용자 자리(auth · clip). `auth` `app_auth` · `clip` `app_clip` · `chat-collector` `app_chat_collector` · `chat-detector` `app_chat_detector` — chat 두 키는 배치(POK-306)가 읽는다. 사용자 · `rds_iam` · GRANT 는 `sql/` 이 만든다 |
| `app_task_resource_arns`(서비스 → 키 → ARN) | platform — 작업 역할 정책 Resource(키 = platform 의 `task_resource_keys` — auth 하나 · clip 여덟). 일꾼 render 넷 · upload 둘은 배치 루트가 읽는다. 썸네일 DLQ 키는 없다 |
| `rds_master_user_secret_arn` | 운영 전용(계약 아님) — SQL 실행기 역할 정책 · 런북. 본 apply 직후 kty 가 이 출력을 `~/.pokeclip-precheck/input-rds-master.txt`(0600)에 적어 2-3 판정이 관리형 마스터 비밀을 묻게 합니다(런북 runtime-1a 3-1). 화면 · 세션에 내지 않습니다 |
| `sql_runner_instance_id` | 운영 전용(계약 아님) — send-command 대상. 꺼져 있으면 null |

비밀은 전체 ARN 으로만 지정합니다. 이름 끝의 「-secret」 을 부분 ARN 의
접미로 오인하는 혼동을 피합니다([prod/secrets](../secrets/README.md)).

## SQL 실행기 스위치

`sql_runner_enabled`(기본 `false`)를 켜면 SQL 을 RDS 에 적용할 작은 상자가
생깁니다 — 역할 · 인스턴스 프로필(경로 `/pokeclip/prod/`) · SG · 규칙 셋 ·
`t4g.micro` AL2023 arm64 인스턴스. 접속은 SSM send-command 뿐입니다(키 · 공인
IP · 인그레스 없음, IMDSv2 필수, 루트 볼륨 암호화). 역할은 SSM 관리형 정책과
관리형 마스터 비밀 하나의 `GetSecretValue` 만 가집니다.

SQL 을 적용한 뒤 곧바로 끕니다. 켜진 동안 `iam:PassRole` 을 가진 dev 주체가
이 프로필로 상자를 띄워 마스터 비밀을 읽을 수 있기 때문입니다. 2-3
판정(principal-check)은 dev 주체가 실행기 역할을 EC2 에 넘길 수 있는지
(`iam:PassRole` · `iam:PassedToService = ec2.amazonaws.com`)를 묻습니다.
켠 뒤 SQL 을 보내기 전에 2-3 판정을 다시 돌립니다 — 켜진 실행기는
`Environment = prod` 태그로 `prod-instance` 행에 풀리고, dev 주체에 그
인스턴스의 `ssm:SendCommand` · `ssm:StartSession` 이 있으면 SQL 을 보내지
않고 끕니다(런북 runtime-1a 4). 잊어도 상자는 5-3 뒤로는 준비 단계가 건
자동 종료로 120분 뒤 멈추지만, 4(켬)에서 5-3 사이에는 시한이
없습니다(런북 4 · 5-3).

## 대안

| 대안 | 고르지 않은 이유 |
|---|---|
| 마스터 비밀을 고객 관리 키(CMK)로 암호화 | 키 정책 · 비용 · 삭제 대기라는 표면이 늘고, 실행기 역할에 kms 권한을 따로 줘야 한다. AWS 관리 키 `aws/secretsmanager` 로 충분하다 |
| `engine_version` 을 부판까지 고정(`17.x`) | AWS 가 부판을 강제로 올리면(보안 · 지원 종료) 다음 apply 가 옛 부판으로 되돌리려다 실패한다. 메이저 `17` 만 고정하면 provider 가 점 경계 접두를 같은 판으로 본다 |
| `lifecycle { ignore_changes = [engine_version] }` | 코드가 실제 판에 대해 거짓말을 하게 되고, 의도한 메이저 업그레이드 때 lifecycle 을 다시 풀어야 한다 |
| 버킷 공용 모듈(rewind · clips · 프로필 사진) | 버킷마다 설정이 갈리고(만료 60일 · 없음) 셋은 아직 작다(제0원칙 — 과설계 금지). 넷째가 생기면 다시 본다 |
| 일 큐 숨김 시간을 변수로 | 일꾼 yml 기본값과 한쪽만 바뀌는 길을 연다 — 상수(`job_queues.tf`)와 그 상수에서 만든 일꾼 env 로 묶는다 |

메이저만 고정한 대가로, 생성 때 부판은 AWS 의 17 기본 부판이라 최신이 아닐
수 있습니다. 실제 값은 런북 「운영 확인」 에서 기록합니다. 이 전제(점 경계
접두 비교)는 mock 시험으로 재지 않습니다.

## 1단-나 이월

런타임 1단-나로 넘긴 일입니다. 앱 서버 DB 사용자 · GRANT · `public` 스키마
CREATE 는 1단-가가 했습니다(POK-407 · kty 2026-10-24). 잊지 않도록 여기 둡니다
(설계 9절 · Jira POK-304 코멘트와 같은 목록).

1.  **advisory 함수 권한 회수(K7)** — `pg_advisory_lock` ·
    `pg_advisory_xact_lock` 등을 PUBLIC 에서 회수할지 정합니다.
    *   미룬 이유 — 쓰는 쪽이 둘입니다. ① 사이드카 `media_indexer` 가 컷오프
        경로에서 `pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(…)::bigint)`
        을 씁니다(`media/internal/index/store.go:206` 의 `lockCutoffStreamSQL`).
        권한 정본은 「advisory 잠금은 PUBLIC 기본으로 충분」 을 전제로 GRANT 를
        두지 않습니다(`media/internal/pgtest/role.go:41`). ② 3번 서버 넷의
        Flyway 가 advisory 잠금으로 마이그레이션을 직렬화합니다(Flyway 일반
        거동 — 3번 코드는 아직 대조하지 않음). PUBLIC 에서 회수만 하면 사이드카
        컷오프가 42501 로 실패하고 앱 서버 부팅이 실패할 수 있습니다.
    *   회수한다면 재부여 대상에 `media_indexer` 를 넣고, `role.go` 의 전제
        주석과 권한 시험을 함께 고칩니다. 재부여를 잊으면 `sql/40_verify.sql`
        의 `advisory_xact_lock_media_indexer` 단언이 깨집니다.
    *   재료: 1단-가 운영에서 돌린 `sql/50_advisory_probe.sql` 의 출력(마스터로
        회수가 되는지 · 문구 · `has_function_privilege`) — 볼트 raw 에
        있습니다.
    *   1단-나에서 할 일: ① 3번 서버 넷의 advisory 사용 실물 대조(Flyway 판 ·
        코드) ② 회수하면 PUBLIC 에서 회수하고 필요한 계정에만 EXECUTE 를 다시
        줌 ③ 회수하지 않으면 사유를 기록.
1.  계정의 TEMP · PUBLIC CONNECT 회수 — media 두 계정과 앱 사용자 넷이 같은
    DB 를 쓰므로 함께 정합니다. CONNECT 를 PUBLIC 에서 회수하면
    `sql/40_verify.sql` 의 `connect:<계정>` 이 깨집니다 — 필요한 계정에 다시
    줍니다.
1.  IAM 인증을 못 쓰는 대체 갈래면 `POSTGRES_PASSWORD` 그릇.
1.  2-3 판정 대상의 남은 칸. 런타임 자원 행(RDS · 로그 그룹 · SNS · SQS ·
    rewind 버킷 · 실행기 역할 · 프로필)은 이 PR 이 더했습니다(설계 K5 는
    바뀌었습니다 — POK-365 완료로 `runtime-1a` 는 O7c 뒤 · O7d 앞의 2-3 판정
    단계, kty 2026-10-23 · 10-24). 남은 것: rewind 행은 O7d 부터(runtime-1a
    직전에는 버킷이 없음) · RDS 관리형 비밀(`rds!db-*`)은 `Environment=prod`
    태그가 붙는지 apply 뒤 실측. SNS 주제 · SQS 큐의 자원 정책은 2-3 판정이
    직접 읽습니다. runtime-1a 직전에는 자원이 없어 정책 없이 판정하고
    `report.txt` 에 「자원 없음」 을 남깁니다.
1.  `scripts/put-secret-from-file.sh` 의 비밀 종류 확장(O7d 앞). 닫힘 —
    1단-다(POK-427): 그릇 이름 허용 목록 · SOOP 포함.
1.  응답기 비밀 최소 권한(K1) — **닫힘 — 1단-나 ②(POK-429)**: Media 가 읽는
    그릇은 resolve 전용 토큰 하나이고(공용 `INTERNAL_API_TOKEN` 없음), auth
    의 전용 창구 출발지는 Media 프라이빗 서브넷입니다. auth 는 POK-316 으로
    `native` 위에서도 그 창구를 엽니다.
1.  앱별 스키마 — 앱 사용자 넷에게 `public` CREATE 대신 자기 스키마를 주고
    (3번 Flyway 의 스키마 설정과 함께) `public` CREATE 를 회수해, 한계 절의
    이름 해석 가로채기를 닫습니다. 3번 Java(`services/auth` 의
    `AudioTrackLabelRepository` · `PairingAttemptRecorder` 의
    `pg_advisory_xact_lock(hashtext(?))`)도 같은 꼴이라 앱 사용자끼리 가로챌
    수 있습니다 — 1단-나 · 3번 협의입니다.

## 한계

고치지 않고 남긴 것입니다(PR 본문에도 한 줄씩).

*   SQL 실행기 SG 의 이그레스 443 은 `0.0.0.0/0` 전체입니다 — SSM 끝점 ·
    패키지 저장소 · CA 번들을 NAT 로 받기 때문이고, VPC 엔드포인트는 두지
    않았습니다.
*   계약9 주제 정책은 SNS 기본 정책(`Principal *` · `AWS:SourceOwner`)
    대신 계정 루트 한 주체에 기본 정책의 동작 여덟을 여는 명시 정책입니다
    (kty 2026-10-24). 누가 쓰는지는 신원 정책이 정하므로, 같은 리스 계정의
    dev 주체가 신원 정책으로 `sns:*` 를 가지면 이 정책이 막지 못합니다 —
    2-3 판정이 그 질문을 합니다. AWS 서비스가 이 계정 대신 발행하는 길
    (`AWS:SourceOwner`)은 닫힙니다.
*   상자가 받는 RDS CA 번들은 지문을 대조하지 않습니다 — HTTPS(curl 기본
    인증서 검증)만 믿습니다.
*   SQL 적용 뒤 마스터 비밀 순환(`rotate-secret`)은 하지 않습니다. 실행기가
    켜진 동안의 노출이 걱정되면 운영자가 고를 수 있는 선택지로 남습니다.
*   관리형 마스터 비밀(`rds!db-*`)의 생성 · 태그 동작은 `runtime-1a` 묶음이
    그릇 경로(`pokeclip/prod/*`)로 대신 판정합니다. SCP 가 그 이름만 거부하면
    점검은 통과하고 apply 가 거부로 멈춥니다(런북 2).
*   앱 사용자 넷이 `public` CREATE 를 가져, 같은 DB 의 다른 계정 세션이 맨
    이름으로 부르는 함수 · 연산자를 가로챌 수 있습니다(CVE-2018-1058 계열).
    사이드카 · janitor SQL 과 `sql/` 의 마스터 SQL(00–50 · 스키마 사본
    `stream_schema.sql`)은 이 PR 에서 `pg_catalog` 로 한정하고 형을
    맞췄습니다(규약은 `media/README.md`). 3번 Java(`services/auth` 의
    `AudioTrackLabelRepository` · `PairingAttemptRecorder` 의
    `pg_advisory_xact_lock(hashtext(?))`)도 같은 꼴이라 앱 사용자끼리 가로챌
    수 있습니다 — 근본(앱별 스키마)과 함께 1단-나 · 3번 협의입니다.
*   일꾼 배치 전에는 소비자가 없습니다(1단-다 K5). O7d 부터 일꾼 배치(POK-408
    배치 PR)까지 clip 이 보낸 주문이 원 큐에 쌓이고, 보관 4일 뒤 사라집니다
    — 고착 알람이 없습니다.
*   segment 창고 = rewind 버킷이라 platform 의 clip `SegmentDelete` ·
    `SegmentRead`(`<버킷>/*`)가 목록 객체까지 지우고 읽을 수 있습니다. 좁히는
    일(지우기 `streams/*` · `dvr/*/init/*` · `dvr/*/seg/*`, 읽기
    `streams/*`)은 platform 후속이고, 런타임 2단 버킷 정책이 작업 역할을 막지
    않게 둡니다.
*   새 버킷 둘(clips · 프로필 사진)에 TLS 거부 버킷 정책이 없습니다 — rewind
    와 함께 런타임 2단에서 둡니다(K8).
*   `clip_job_features` 의 거부 규칙(render · upload 둘 다 끔)은 런타임 변수
    값만 막습니다. 코드 되돌리기로 env 가 빠져 yml 기본값(둘 다 false)으로
    도는 길은 [runtime-1a 런북](../../runbooks/runtime-1a.md) 「1단-다 —
    되돌리기」 의 「clip 먼저 0개」 절차가 막습니다.
*   탈퇴 정리 로그 `clip.purge.segments_skipped`(`StreamerPurger.java:78`)는
    손실 사건입니다 — 그 스트리머의 녹화 조각이 지워지지 않은 채 명부가
    닫힌 것입니다. 나오면 스트리머 id 를 모아 kty 에게 회부합니다(런북).
*   auth 의 resolve 전용 창구 출발지는 Media 프라이빗 서브넷 대역 하나라,
    같은 AZ 에 뜨는 ECS 태스크(next · auth · clip · chat · 일꾼)도 출발지
    조건은 통과합니다. 그 서브넷이 `private_subnet_ids` 의 한 칸이기
    때문입니다. 1차 방어는 토큰입니다(POK-429 K3).
*   전용 토큰 · 공용 토큰을 바꾸거나 긴급 차단할 때 auth 롤링 배포 동안 옛
    태스크 · 새 태스크가 섞여 어느 토큰이든 일부 요청이 401 입니다(거부 창 —
    진행 중인 송출은 무사). 순서와 완료 조건은
    [runtime-1a 런북](../../runbooks/runtime-1a.md) 「1단-나 ②」 절입니다.
*   Media 상자는 IMDS 홉 제한이 2(`modules/media_host/main.tf` 의
    `http_put_response_hop_limit`)라 상자의 컨테이너도 인스턴스 역할 자격을
    받습니다. 그래서 전용 토큰을 읽을 수 있는 것은 응답기만이 아니라 상자
    위의 모든 것입니다. media_host 범위라 고치지 않았습니다.

## See also

*   [런타임 트랙 인터페이스 계약](../../CONTRACT-runtime.md)
*   [runtime-1a 런북](../../runbooks/runtime-1a.md) ·
    [단계 직전 점검](../../runbooks/stage-precheck.md)
*   [ADR-0001 stream_segments DDL 소유권](../../../../docs/decisions/2026-08-01-stream-segments-ddl-ownership.md)
