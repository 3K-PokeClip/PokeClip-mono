# nonprod/bootstrap

nonprod 계정의 Terraform 상태 버킷을 만드는 루트입니다. 상태 버킷이 없으면
다른 nonprod 루트(dev · dns-legacy · dns-dev)가 상태를 둘 곳이 없으므로 이
루트를 가장 먼저 적용합니다.

버킷 설정(버전 관리 · SSE · 퍼블릭 차단 · ACL 끔 · TLS 강제 정책 ·
이전 버전 90일 만료 · `prevent_destroy`)은 `modules/state_bucket` 이
정합니다. 잠금은 `use_lockfile = true` 입니다.

## 2단 절차(운영 O1 — kty 승인)

자기 상태를 담을 버킷을 자기가 만들어야 하므로 두 단계로 나눕니다.

1.  값 파일을 만듭니다.

    ```
    cp terraform.tfvars.example terraform.tfvars   # 실제 버킷 이름
    cp backend.hcl.example backend.hcl             # 같은 버킷 이름
    ```

1.  **로컬 상태로 생성합니다.** `backend.tf` 의 s3 백엔드를 덮는 override
    파일을 잠시 둡니다(Terraform 표준 override 파일 · 추적하지 않음).

    ```
    printf 'terraform {\n  backend "local" {}\n}\n' > backend_override.tf
    terraform init
    terraform plan -out=bootstrap.tfplan    # 생성 7 · 그 밖 0 확인
    terraform apply bootstrap.tfplan
    ```

1.  **자기 버킷으로 이관합니다.** override 파일을 지우고 s3 백엔드로
    다시 init 하면서 상태를 옮깁니다.

    ```
    rm backend_override.tf
    terraform init -migrate-state -backend-config=backend.hcl
    terraform plan -lock=false              # No changes 확인
    rm terraform.tfstate terraform.tfstate.backup bootstrap.tfplan
    ```

로컬 `terraform.tfstate` 는 이관이 끝나고 「No changes」를 본 뒤에 지웁니다.

## 출력

| 출력 | 쓰는 곳 |
|---|---|
| `state_bucket_name` | nonprod 각 루트 `backend.hcl` 의 `bucket` |
| `state_bucket_arn` | 참고용 |
