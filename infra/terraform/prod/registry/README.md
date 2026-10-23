# prod/registry

prod 이미지를 담는 ECR 저장소를 만드는 루트입니다(설계 3-1 의 ⑦). 저장소만
만듭니다 — 이미지 푸시는 당분간 kty 로컬이고, 절차는
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
*   **IAM 없음**: CI 푸시 역할 · apex 업로드 역할 · GitHub OIDC
    공급자(자원 · data source)는 이 루트에 두지 않습니다. CI 자동 푸시를
    켜는 후속 승인 지점(설계 8절 · 40차)의 PR 이 함께 더합니다. tftest 로
    부재를 볼 수 없어 리뷰가 확인합니다.

provider 의 `default_tags` 네 키(`TerraformRoot = prod/registry`)는 태그를
받는 자원(`tags_all` 이 있는 자원 — 저장소)에 붙습니다. mock 시험은 태그를
보지 못하므로 plan 의 `tags_all` 로 확인합니다.

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

## 출력

소비 루트와의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `repo_urls`(키 → 저장소 URL) | platform · media — 이미지 주소 · kty 로컬 푸시(레지스트리 호스트) |
