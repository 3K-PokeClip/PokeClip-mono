# prod/media

prod Media 상자[^box]를 띄우는 루트입니다(설계 3-1 의 ⑨). 자원은
[modules/media_host](/infra/terraform/modules/media_host/README.md) 가 만들고,
이 루트는 앞 루트의 출력과 이미지 참조를 그 모듈에 잇습니다.

*   **상자:** Graviton(arm64 — 결정 146) EC2 를 Media AZ 프라이빗 서브넷에
    둡니다. 유형 · 디스크는 `c8gn.large` · gp3 150 GiB 입니다(kty 확정
    2026-10-23).
*   **대상 등록:** NLB `ingest` 에는 `ingest_active_key` 상자를, 내부 ALB
    `live` · `control` · `playback` 에는 `http_active_key` 상자를 등록합니다.
    공개 ALB 에는 등록하지 않습니다.
*   **SG 짝 규칙:** Media SG 와 함께 RDS 인그레스 ← Media(5432) · 내부 ALB ·
    NLB 이그레스 → Media 를 이 루트의 apply 가 만듭니다(설계 5-5 — 짝이 한
    apply 에 생깁니다).
*   **인스턴스 역할:** 경로 `/pokeclip/prod/`. 비밀 읽기 · ECR 끌어오기 ·
    `rds-db:connect`(`media_indexer` · `media_janitor` 둘) · rewind 버킷 쓰기 ·
    응답기 거부 목록 읽기.
*   **응답기 거부 목록:** M15 응답기가 읽을 SSM 파라미터 그릇을 둡니다.
    Terraform 은 첫 줄만 넣고 값은 운영자가 고칩니다.

교체(활성 키 이동 · 설정 반영 · force)는
[Media 교체 런북](/infra/terraform/runbooks/media-swap.md)을 따릅니다.

이 루트에 **없는** 것은 다음과 같습니다.

| 자원 | 루트 |
|---|---|
| NLB · 내부 ALB · 대상 그룹 · LB SG 자체 | platform |
| Media 이그레스 → auth · auth 인그레스 ← Media | 런타임 2단(이 루트의 `media_sg_id` 를 씀) |
| RDS · DB 사용자 · rewind 버킷 · 런타임 비밀 그릇 | 런타임 1단 |
| CDN 비밀 · 시험 키 그릇 | secrets(값은 [엣지 비밀 런북](/infra/terraform/runbooks/edge-secrets.md)) |

## 앞 루트 출력

`terraform_remote_state` 로 읽습니다(키는 `state_keys` 기본값).

| 루트 | 출력 |
|---|---|
| network | `vpc_id` · `media_private_subnet_id` · `s3_prefix_list_id` |
| platform | `target_group_arns` · `internal_alb_sg_id` · `nlb_sg_id` · `media_proxy_ports` · `media_health_path` |
| secrets | `cdn_secret_arn` · `test_stream_key_secret_arn` |
| registry | `repo_urls`(아래 「이미지」) |
| 런타임 1단 | `rds_endpoint` · `rds_db_name` · `rds_resource_id` · `rds_sg_id` · `rewind_bucket_arn` · `media_secret_arns`([CONTRACT-runtime.md](/infra/terraform/CONTRACT-runtime.md)) |

출력 하나라도 없으면 plan 이 그 이름으로 실패합니다. 런타임 1단 출력은 O7e
선행 확인 ② 입니다.

## 이미지

이미지 참조는 `<repo_urls[저장소 키]>@<다이제스트>` 로 조립합니다. 다이제스트만
tfvars 에 둡니다.

| `image_digests` 키 | 저장소 키 |
|---|---|
| `bundle` | `media-host-bundle`(설치 묶음) |
| `mediamtx` | `media-mediamtx` |
| `nginx` | `media-nginx` |
| `sidecar` | `media-sidecar`(janitor 도 이 이미지) |
| `loopback_sender` | `media-loopback-sender` |
| `ingest_auth` | `media-ingest-auth` — 비우면 렌더하지 않습니다(M15) |

*   `media-host-bundle` · `media-loopback-sender` 는 M9 에서 `prod/registry` 에
    더한 저장소입니다. registry 를 다시 apply 하기 전이면 plan 이 그 키로
    실패합니다([prod/registry](/infra/terraform/prod/registry/README.md)
    「적용」).
*   다이제스트는 O7c 의
    [prod 산출물 올리기 런북](/infra/terraform/runbooks/prod-artifacts.md)이
    푸시하고 기록한 값입니다. 모두 arm64 단일 변형이어야 합니다.

## 값과 적용

`terraform.tfvars.example` 을 `terraform.tfvars`(추적 안 함)로 복사해
채웁니다.

*   nginx 포트 · 헬스 경로는 tfvars 에 두지 않습니다. 정본은 platform 이고
    (대상 그룹과 같은 값), 이 루트는 출력 `media_proxy_ports` ·
    `media_health_path` 를 읽습니다. platform 에서 값을 바꾸면 이 루트도
    설정 반영 교체로 새 상자를 띄웁니다 — 떠 있는 상자는 옛 포트로 듣습니다.
*   도구 판(`docker_version` · `ecr_credential_helper_version` ·
    `compose_version` · `compose_sha256`)은 예시 파일의 값을 그대로 씁니다
    (출처 · 날짜는 modules/media_host README 「판 고정」).
*   `control_upstream` 은 기본 빈 값(nginx 제어면 404), `janitor_enabled` 는
    기본 켬입니다. 둘 다 바꾸면 설정 반영 교체로 들어갑니다 — 떠 있는
    상자의 user_data 는 바뀌지 않습니다.
*   `rewind_public_base_url` 은 기본 `https://media.pokeclip.com` 입니다.
    사이드카 `REWIND_PUBLIC_BASE_URL`(되감기 목록 URI 앞머리 — 목록 줄 = 이
    값 + `/` + S3 키 `dvr/…`)이고, 영상 CDN 의 `/dvr/*` 가 rewind 버킷입니다
    (설계 4-5). 버킷이 있으면 사이드카가 이 값 없이 기동하지 않습니다.

적용은 O7e 의 1(kty 승인)입니다. 백엔드는 `backend.hcl.example` 을
`backend.hcl` 로 복사하고 `bucket` 자리표시자 `<STATE_BUCKET_PROD>` 를
`pokeclip-tfstate-prod-apne2` 로 고친 뒤 `init -backend-config=backend.hcl` 로
엽니다(운영 단계).

provider 의 `default_tags` 네 키(`TerraformRoot = prod/media`)는 태그를 받는
자원(`tags_all` 이 있는 자원)에 붙습니다. mock 시험은 태그를 보지 못하므로
plan 의 `tags_all` 로 확인합니다.

## 출력

소비 루트 · 운영과의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `ingest_active_key` · `http_active_key` | 교체 런북 — 일관성 확인 |
| `media_sg_id` | 런타임 2단 — Media → auth 이그레스 · auth 인그레스 ← Media |
| `instance_ids`(상자 키 → id) | 교체 런북 — SSM 명령 대상 |

모듈의 시험용 출력(`rendered_user_data` · `template_vars`)은 다시 내보내지
않습니다.

## 시험

`terraform -chdir=infra/terraform/prod/media test`(mock provider · 앞 루트
상태는 `override_data`).

*   이미지 참조 조립(저장소 · 다이제스트 짝 · 빈 응답기), 런타임 · secrets ·
    platform(포트 · 헬스 경로) 출력이 사이드카 env · `media.env` · nginx
    변수에 닿는지, 기본값(janitor 켬 · 제어면 상류 빈 값), 출력 넷,
    다이제스트 꼴 검증을 봅니다.
*   **시험으로 볼 수 없는 것:** 루트 시험은 하위 모듈 안의 자원을 주소로 볼
    수 없습니다. 그래서 그대로 넘기는 선 — `subnet_id` · `vpc_id` · 대상
    그룹 · LB SG · `rds_sg_id` · `rds_resource_id` · `media_secret_arns` — 은
    O7e 1 의 plan 리뷰로 확인합니다(교체 런북 「plan 범위」). 모듈 안 동작은
    modules/media_host 시험이 맡습니다.

[^box]: **상자** — prod 미디어 서버를 돌리는 EC2 인스턴스 한 대.
