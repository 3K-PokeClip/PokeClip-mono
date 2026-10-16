# prod/secrets

prod 엣지의 비밀 그릇 세 개와 CloudFront 서명 공개키를 만드는 루트입니다
(설계 3-1 의 ②). 그릇에는 값이 없습니다 — 값은 운영 단계에서 Terraform 밖으로
넣으므로 상태 파일에 비밀 값이 남지 않습니다(guard R1 · R6).

| 비밀 이름 | 담는 값 | 넣는 단계 |
|---|---|---|
| `pokeclip/prod/signing-key` | CloudFront 서명 개인키(RSA 2048 · PKCS#8 PEM) | O5 |
| `pokeclip/prod/cdn-secret` | CloudFront 가 원본 요청에 붙이는 Bearer | O7e(T9a 관찰 뒤) |
| `pokeclip/prod/test-stream-key` | 루프백 시험용 스트림 키 | O7f(설계 5-4 의 11 — 루프백 시험 직전) |

공개키 이름은 `pokeclip-prod-signing-key` 입니다. 공개키 PEM 은 비밀이 아니지만
저장소에 두지 않고 `public_key_pem` 변수로 받습니다 — 추적하지 않는
`terraform.tfvars`(`terraform.tfvars.example` 참고) 또는
`TF_VAR_public_key_pem` 입니다. 끝 줄바꿈의 유무는 모듈이 하나로 맞춥니다.

provider 의 `default_tags` 네 키(`TerraformRoot = prod/secrets`)는 plan 의
`tags_all` 로 확인합니다(mock 시험이 보지 못함).

## 적용과 값 입력

순서 · 명령 · 로컬 파일 규칙은
[엣지 비밀 런북](../../runbooks/edge-secrets.md)에 있습니다. 백엔드는
`backend.hcl.example` 을 `backend.hcl` 로 복사해(버킷 =
`pokeclip-tfstate-prod-apne2`) `init -backend-config=backend.hcl` 로
엽니다(운영 단계 · kty 승인).

## 출력

소비 루트와의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `public_key_id` | edge — 키 그룹 |
| `signing_key_secret_arn` | platform — clip secrets · 실행 역할 읽기 정책 |
| `cdn_secret_arn` | media — 인스턴스 역할 읽기 정책 · 시작 스크립트 |
| `test_stream_key_secret_arn` | media — 인스턴스 역할 읽기 정책 · 루프백 스크립트 |
