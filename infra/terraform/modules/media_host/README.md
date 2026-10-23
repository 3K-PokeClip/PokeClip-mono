# modules/media_host

Media 상자[^box] 모듈입니다(설계 3-3 · 3-3-1 · 5-3 · 5-5, 계획 M9). `prod/media`
루트가 부릅니다. Graviton(arm64) EC2 · Media SG 와 짝 규칙 · 인스턴스 역할 ·
대상 등록 · 응답기 거부 목록 SSM 파라미터를 만듭니다.

*   **상자:** AL2023 arm64 AMI(SSM 공개 파라미터)를 Media AZ 프라이빗
    서브넷에 둡니다. 공인 IP 가 없고, IMDSv2 필수에 hop limit 2 입니다 —
    컨테이너(사이드카 IAM DB 토큰 · S3 업로드)가 인스턴스 역할 자격을 얻어야
    하기 때문입니다.
*   **교체 모델(5-3):** 상자 키마다 `config_version` 을 둡니다. 이미 떠 있는
    키의 user_data · AMI 는 바꾸지 않고(`ignore_changes`), 새 구성은 새 키로
    띄운 뒤 `ingest_active_key` · `http_active_key` 를 옮깁니다. 대상 그룹
    넷에는 활성 키의 상자 하나만 등록합니다.
*   **SG(5-5):** Media SG 인그레스는 SRT ← NLB · 프록시 셋 ← 내부 ALB ·
    헬스 ← 둘입니다. 이그레스는 443 → S3 prefix list · 443 → 0.0.0.0/0 ·
    5432 → RDS SG 입니다. RDS 인그레스 ← Media 5432 와 로드밸런서 SG
    이그레스 → Media 도 이 모듈이 만듭니다(한 apply 에 짝이 생깁니다).
    MediaMTX 루프백 포트(8888 · 9996 · 9997)는 열지 않습니다.
*   **인스턴스 역할:** 경로 `/pokeclip/prod/`. 관리형 정책은
    `AmazonSSMManagedInstanceCore` 하나이고, 권한 정책은 정책 문서
    (`aws_iam_policy_document`)를 역할 인라인 정책으로 붙입니다. guard R10
    (`policy/guard/media_role.rego`)이 이 꼴을 지킵니다.
    *   관리형 정책: 문자열 속 `iam::aws:policy/<이름>` 은 SSM Core 만 · 연결은
        주소 `managed` 하나(`for_each = local.managed_policy_arns` ·
        `policy_arn = each.value`, 지역값은 SSM Core 하나) · 정책 조회
        (`data "aws_iam_policy"`) · 고객 관리 정책 · 다른 IAM 정책 유형 금지.
    *   정책 문서: `not_actions` · `not_resources` · 다른 문서 합치기 ·
        `dynamic` 금지. 역할 인라인 정책은 정책 문서 json 으로만(jsonencode ·
        역할의 `inline_policy` · `managed_policy_arns` 금지 — 신뢰 정책은
        예외).
    *   DB: `rds-db:connect` 는 정책 문서 statement 에만 · Resource 는
        `local.db_users` 를 도는 for 식만 · `db_users` 는 두 이름
        (`media_indexer` · `media_janitor`) 리터럴.
    *   statement `actions` 는 `서비스:동작` 꼴 리터럴 목록만(허용 목록 —
        와일드카드 · 지시자 · 식 금지). 두 경로의 문자열에 템플릿 지시자 `%{`
        금지.
    *   lifecycle: 거부 목록 파라미터는 `prevent_destroy = true`(리터럴) ·
        `ignore_changes` 에 `value`, 상자는 `ignore_changes` 에 `ami` ·
        `user_data_base64`.

## 설치 경로 — 설치 묶음 이미지

압축 user_data 는 EC2 한도 16 KiB 안이어야 합니다. `infra/media-host/` 의
스크립트 · 템플릿을 모두 user_data 에 넣으면 압축본이 35,553 바이트라 넘습니다
(2026-10-23 실측). 그래서 kty 결정(2026-10-23)으로 둘로 나눕니다.

| 어디에 | 무엇 | 소유 · 모드 |
|---|---|---|
| 설치 묶음 이미지(`infra/media-host/bundle/Dockerfile` — `pokeclip-prod/media-host-bundle`) | `bin/*.sh` → `/opt/pokeclip-media/bin/` | `root:root` · 0755 |
| 같은 이미지 | `bin/lib/*` → `/opt/pokeclip-media/bin/lib/` | `root:root` · 0644(디렉터리 0755) |
| 같은 이미지 | `mediamtx.yml.tmpl` → `/etc/pokeclip-media/mediamtx.yml.tmpl` | `root:root` · 0644 |
| user_data | `compose.yml` · `nginx.conf`(Terraform 렌더) · `media.env` | `root:root` · 0644 |
| user_data | `/root/.docker/config.json`(`credHelpers` → `ecr-login`) | `root:root` · 0600 |
| user_data | 유닛 `pokeclip-media.service`(저장소 파일 그대로) · drop-in `10-bundle.conf` | `root:root` · 0644 |
| user_data | journald 설정(`Storage=persistent`) | `root:root` · 0644 |
| user_data | 부트스트랩 `/usr/local/sbin/pokeclip-media-bootstrap` | `root:root` · 0700 |

*   부트스트랩 순서: docker · ECR 자격 도우미 · compose 설치 → docker 기동 →
    묶음을 `docker create` · `docker cp` 로 꺼내 위 소유 · 모드로 설치 →
    설치 표지(`/var/lib/pokeclip-media/bundle-installed`) → 유닛 enable ·
    start.
*   **설치 실패도 기동 차단:** 어느 단계가 실패해도 유닛은 enable · start
    합니다. 묶음 표지가 없으면 drop-in 이 `MEDIA PREREQ FAIL bundle`, 도구가
    빠졌으면 시작 스크립트가 `MEDIA PREREQ FAIL <항목>` 으로 막습니다. 둘 다
    유닛 저널에 남고 20초마다 다시 시도합니다. 유닛을 묶음이 아니라
    user_data 에 두는 이유가 이것입니다 — 묶음을 꺼내지 못해도 유닛이 있어야
    합니다.
*   부트스트랩의 판정 줄(`MEDIA BOOTSTRAP OK|FAIL <단계>`)은 cloud-init 출력
    (`/var/log/cloud-init-output.log`)에 남습니다.
*   압축 user_data 크기(시험값 기준): 기본 12,381 바이트 · 가장 큰 렌더(응답기
    · janitor · 제어면 상류 모두 켬) 12,453 바이트 — 여유 약 3.9 KB 입니다.
    시험이 매번 다시 잽니다.

묶음 내용이 저장소와 같은지는 묶음 Dockerfile 이 저장소 경로를 그대로 COPY
하는 것으로 갈음합니다. 2026-10-23 로컬 빌드(arm64)에서 꺼낸 9개 파일의
sha256 이 저장소와 같았습니다.

## 판 고정

| 도구 | 판 | 출처 · 확인 |
|---|---|---|
| docker compose 플러그인 | 5.5.0 · `docker-compose-linux-aarch64` sha256 `ff42489f5a9b879d5d117c5ffea6defc27390b3286da8ad52cbc9c6ab5df590e` | 공식 릴리스 [docker/compose v5.5.0](https://github.com/docker/compose/releases/tag/v5.5.0)(게시 2026-08-17)의 `.sha256` 자산과 릴리스 API 의 자산 digest — 2026-10-23 조회 |
| docker | 25.0.16 | `infra/media-host` README 「판 고정」(M2 탐침 상자 실측) |
| `amazon-ecr-credential-helper` | 0.12.0-3.amzn2023 | 같은 표(AL2023 저장소 2026-10-20 조회) |

*   값은 루트 tfvars 가 넘깁니다(이 모듈에 기본값 없음). compose 판을 바꾸면
    `compose_sha256` 도 함께 바꿉니다 — 하나만 바꾸면 체크섬 확인이 실패하거나
    `MEDIA PREREQ FAIL compose-version` 으로 기동이 막힙니다.
*   AL2023 **arm64** 저장소에 docker 25.0.16 · 자격 도우미 0.12.0-3 이 있는지는
    **미확인**입니다 — O7e 에서 확인합니다.

## 상자 크기

kty 확정(2026-10-23): `c8gn.large`(네트워크 최적화 Graviton) · 루트 gp3
150 GiB · 처리량 125 MiB/s(gp3 기본) · 암호화 · 종료 때 삭제.

*   유형: 2026-09-17 원가 문서 결정(동시 30방송 · 대역폭 기준으로
    `c8gn.large`). 2026-10-23 `describe-instance-types` 읽기에서 상시 네트워크
    대역폭 6.25 Gbps · EBS 상시 처리량 156 MB/s 였습니다(같은 크기의
    `c7g.large` 는 상시 0.937 Gbps).
*   디스크: DVR 1시간 × 30방송 ≈ 125 GB 에 OS 몫을 더한 150 GiB 입니다.
    처리량 변수는 125 부터 750 MiB/s 까지 받습니다 — gp3 는 IOPS 당
    0.25 MiB/s 까지이고 이 모듈은 IOPS 를 기본 3,000 으로 둡니다.
*   유형은 입력 단계에서 계열 이름(세대 숫자 뒤 `g`)으로 한 번 거르고, plan
    때 AWS 가 답하는 지원 아키텍처로 다시 확인합니다.
*   값은 `prod/media` 의 `terraform.tfvars.example` 이 적고, 이 모듈은 변수 ·
    검증만 둡니다.

## 입력에서 주의할 것

*   `proxy_ports`(live · playback · control · health)는 1024 이상이고 서로
    다르며, 응답기 포트(8080 · 8081)와 MediaMTX 루프백 포트(8888 · 9996 ·
    9997)가 아닙니다. 같은 망 칸[^netns]에서 겹치면 nginx 가 뜨지 않습니다.
*   이미지 참조는 모두 `<저장소>@sha256:<64자>` 이고 이 계정 ECR 레지스트리
    호스트(`media.env` 의 `ECR_REGISTRY_HOST`)로 시작해야 합니다. 응답기
    (`ingest_auth_image`)만 빈 값을 받습니다 — 비면 렌더하지 않습니다(M15).
*   `instance_type` · `root_volume_size` 는 기본값이 없고 루트 tfvars 가
    넘깁니다(아래 「상자 크기」). `instance_type` 은 AWS 가 답하는 지원
    아키텍처(`aws_ec2_instance_type`)에 arm64 가 없으면 plan 이 멈춥니다.
*   `rewind_bucket_arn` 에서 버킷 이름을 꺼내 사이드카 `S3_BUCKET` 으로
    넘깁니다.
*   응답기 거부 목록 파라미터 이름 `/pokeclip/prod/media/ingest-auth-deny-tokens`
    는 후보입니다. M15 계획 게이트에서 확정되면 맞춥니다. 값은 운영자가
    고치고 Terraform 은 첫 줄만 넣습니다(`ignore_changes = [value]` ·
    `prevent_destroy`).

## 출력

| 출력 | 쓰는 곳 |
|---|---|
| `instance_ids` | 루트 · 운영 확인 |
| `media_sg_id` | 런타임 2단 — Media → auth 이그레스 · auth 인그레스 ← Media |
| `instance_role_arn` | 참고용 |
| `rendered_user_data` · `template_vars` | 시험용(비밀 없음) — 루트는 다시 내보내지 않습니다 |

## 시험

`terraform -chdir=infra/terraform/modules/media_host test`(mock provider).

*   정책 문서의 `json` 은 mock 이 지어내므로 `statement` 입력을 직접 봅니다.
*   compose · nginx 렌더본은 저장소 템플릿을 같은 변수로 다시 렌더한 값과
    같은지, 템플릿 변수 키 집합은 `infra/media-host/test/render_vars.json` 과
    같은지 봅니다.
*   **시험으로 볼 수 없는 것:** `lifecycle` 의 `ignore_changes` ·
    `prevent_destroy` 는 plan 결과에 나오지 않아 guard R10 이 선언을 봅니다.
    상자 위 동작(부트스트랩 · drop-in · ExecStartPre 실패 뒤 재시작)은 O7e
    에서 상자로 확인합니다.

[^box]: **상자** — prod 미디어 서버를 돌리는 EC2 인스턴스 한 대.
[^netns]: **망 칸** — 리눅스 네트워크 네임스페이스. compose 의 nginx · 사이드카
    · 응답기 · janitor 가 MediaMTX 의 칸을 함께 써 포트가 겹치면 안 됩니다.
