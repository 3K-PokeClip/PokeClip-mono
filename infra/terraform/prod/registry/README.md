# prod/registry

prod 이미지를 담는 ECR 저장소와, GitHub Actions 가 OIDC 로 받는 CI 역할
둘을 만드는 루트입니다(설계 3-1 의 ⑦ · prod CI B12). 수동 경로는
[prod 산출물 올리기 런북](/infra/terraform/runbooks/prod-artifacts.md)입니다.

| 저장소 | 담는 이미지 |
|---|---|
| `pokeclip-prod/next` | 2번 Next.js |
| `pokeclip-prod/auth` · `pokeclip-prod/clip` | 3번 auth · clip |
| `pokeclip-prod/media-nginx` | Media 상자 앞단 nginx(공식 이미지 미러 — 빌드 없음) |
| `pokeclip-prod/media-mediamtx` | MediaMTX(`media/Dockerfile.mtxhook` 빌드) |
| `pokeclip-prod/media-sidecar` | 사이드카(`media/Dockerfile` 빌드) |
| `pokeclip-prod/media-ingest-auth` | 응답기(`media/Dockerfile.ingest-auth` 빌드) |
| `pokeclip-prod/media-loopback-sender` | Media 상자 루프백 송출기(`linuxserver/ffmpeg` 미러 — 빌드 없음, 핀은 `infra/media-host/images.json`) |
| `pokeclip-prod/media-host-bundle` | Media 상자 설치 묶음(`infra/media-host/bundle/Dockerfile` 빌드 — 스크립트 · MediaMTX 템플릿) |
| `pokeclip-prod/render` | 3번 렌더 워커(`workers/render/Dockerfile` 빌드) |
| `pokeclip-prod/upload` | 3번 업로드 워커(`workers/upload/Dockerfile` 빌드) |
| `pokeclip-prod/chat-collector` | 3번 채팅 수집 서버(`services/chat-collector/Dockerfile` 빌드) |
| `pokeclip-prod/chat-detector` | 3번 채팅 판별 서버(`services/chat-detector/Dockerfile` 빌드) |

janitor 는 사이드카 이미지를 씁니다(entrypoint 로 명령을 고름) — 저장소를
따로 두지 않습니다.

*   **태그 불변 · 푸시 때 스캔**: 둘 다 켭니다(`image_tag_immutable` ·
    `scan_on_push` 기본 `true`). 설계에 없는 것이라 변수로 둡니다(kty 47차
    수용). 태그가 불변이라 같은 태그를 다시 푸시하면 실패하는 것이
    정상입니다.
*   **수명주기 정책 없음 · `force_delete` 꺼짐**: tfvars 에 박은 다이제스트의
    이미지가 정책이나 저장소 삭제로 지워지지 않게 합니다. 이미지가 남은
    저장소를 지우는 plan 은 apply 에서 실패합니다.
*   **CI 역할 둘**(`ci_roles.tf`): 아래 「CI 역할」 절입니다.

provider 의 `default_tags` 네 키(`TerraformRoot = prod/registry`)는 태그를
받는 자원(`tags_all` 이 있는 자원 — 저장소 · CI 역할)에 붙습니다. mock
시험은 태그를 보지 못하므로 plan 의 `tags_all` 로 확인합니다.

## CI 역할

GitHub Actions 의 `prod-deploy` environment 잡이 받는 역할입니다. 둘 다
경로 `/pokeclip/prod/` 이고 신뢰가 같습니다.

| 역할(주소) | 이름 | 권한 |
|---|---|---|
| `aws_iam_role.ci_push` | `pokeclip-prod-ci-ecr-push` | 이 루트의 인라인 정책 — 인증 토큰(`*`) · 이 루트 저장소에만 푸시 일곱 동작(삭제 · 정책 · 설정 없음) |
| `aws_iam_role.apex_upload` | `pokeclip-prod-ci-apex-upload` | 이 루트에는 없음 — [prod/edge](/infra/terraform/prod/edge/README.md) 가 apex 업로드 정책을 붙임 |

*   **신뢰**: 계정에 이미 있는 GitHub OIDC 공급자(dev CI 도 씀 — 만들지도
    import 하지도 않고 data source 가 URL 로 찾음)의
    `sts:AssumeRoleWithWebIdentity` 하나입니다. 조건은 `StringEquals` 두
    키뿐입니다 — `aud` = `sts.amazonaws.com`, `sub` = 아래 고정 subject
    꼴입니다.

    ```text
    repo:3K-PokeClip@304758179/PokeClip-mono@1311593360:environment:prod-deploy
    ```

    숫자는 GitHub 의 소유자 · 저장소 id 라, 저장소 이름이 같은 다른
    저장소의 토큰은 맞지 않습니다. main 브랜치 · 필수 승인자는 environment
    보호 규칙이 겁니다.
*   **경계(guard R7)**: 신원 · 신뢰는 이 루트, apex 권한은 버킷 · 배포가
    있는 edge 입니다. 이 루트의 역할 정책 자원은 CI 푸시 역할만 가리킬 수
    있고, 역할 본문의 정책 인자(`inline_policy` · `managed_policy_arns`)는
    둘 수 없습니다.
*   **BatchGetImage · DescribeImages**: 푸시 동작에 넣습니다 —
    BatchGetImage 는 AWS 푸시 예시 정책(ECR 사용 설명서 「image-push-iam」)
    대로, DescribeImages 는 푸시 뒤 다이제스트 · 같은 태그 확인입니다.

## 적용

`terraform.tfvars` 가 필요 없습니다(기본값). 적용은 O7b 의 2(kty
승인)입니다. 백엔드는 `backend.hcl.example` 을 `backend.hcl` 로 복사하고
`bucket` 자리표시자 `<STATE_BUCKET_PROD>` 를 `pokeclip-tfstate-prod-apne2` 로
고친 뒤 `init -backend-config=backend.hcl` 로 엽니다(운영 단계).

**저장소 둘을 더한 뒤의 재적용(M9):** O7b 로 저장소 11개를 만든 뒤
`media-loopback-sender` · `media-host-bundle` 을 더했습니다. `prod/media` 가
두 저장소의 `repo_urls` 를 읽으므로, O7c 에서 두 이미지를 올리기 전에 이
루트를 한 번 더 apply 합니다(kty 승인). plan 은 `aws_ecr_repository.this` 두
개 생성만이어야 합니다(변경 · 삭제 0). 그 밖의 줄이 보이면 적용하지
않습니다.

**CI 역할 추가 적용(prod CI B12):** 단계 직전 점검(공통 묶음
`ci-followup`) 뒤 plan 은 `aws_iam_role.ci_push` · `aws_iam_role.apex_upload`
· `aws_iam_role_policy.ci_push` 세 개 생성만이어야 합니다(저장소 변경 · 삭제
0). 그 밖의 줄이 보이면 적용하지 않습니다. apply 뒤 다시 plan 해 변경 0 을
보고, 이어서 prod/edge 를 apply 합니다(정책 생성 하나 — [edge
README](/infra/terraform/prod/edge/README.md) 「apex 업로드 정책 적용 · 되돌림」).

**되돌림:** prod/edge 의 정책을 먼저 지우고 이 루트의 역할을 지웁니다 —
정책이 남은 역할은 지워지지 않습니다. OIDC 공급자는 dev 도 쓰므로 건드리지
않습니다.

## 출력

소비 루트와의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `repo_urls`(키 → 저장소 URL) | platform · media — 이미지 주소 · kty 로컬 푸시(레지스트리 호스트) |
| `ci_push_role_arn` | GitHub environment `prod-deploy` 비밀 — 이미지 워크플로의 역할 받기 |
| `apex_upload_role_name` | prod/edge — apex 업로드 정책을 붙일 역할 |
| `apex_upload_role_arn` | GitHub environment `prod-deploy` 비밀 — 랜딩 워크플로의 역할 받기 |

역할 ARN 에는 계정 ID 가 들어 있습니다. 공개 저장소의 로그 · 문서에 적지 않고
environment 비밀로만 넘깁니다. `sensitive` 는 붙이지 않습니다 — 계정 ID 가
든 `repo_urls` 와 같은 관례이고, 출력은 화면에 띄우지 않고 파이프로만
씁니다(`terraform output -raw … | gh secret set …`).
