# prod/bootstrap

prod 환경의 Terraform 상태 버킷 `pokeclip-tfstate-prod-apne2` 를 만드는
루트입니다. nonprod 와 같은 리스 계정 안에 별도 버킷으로 둡니다(결정 44 ③ ·
설계 3-4). 상태 버킷이 없으면 다른 prod 루트가 상태를 둘 곳이 없으므로 이
루트를 가장 먼저 적용합니다.

버킷 이름은 고정이라 `state_bucket_name` 기본값으로 둡니다(`terraform.tfvars`
가 필요 없습니다). 버킷 설정(버전 관리 · SSE · 퍼블릭 차단 · ACL 끔 · TLS
강제 정책 · 이전 버전 90일 만료 · 최근 이전 버전 5개 보존 ·
`prevent_destroy`)은 `modules/state_bucket` 이 정합니다. 잠금은
`use_lockfile = true` 입니다.

provider 의 `default_tags` 네 키(`Project` · `Environment` · `ManagedBy` ·
`TerraformRoot = prod/bootstrap`)가 자원마다 붙습니다. mock 시험은 이것을
보지 못하므로 plan 의 `tags_all` 로 확인합니다.

## 2단 절차(운영 O5 의 1 — kty 승인)

자기 상태를 담을 버킷을 자기가 만들어야 하므로 두 단계로 나눕니다. 시작
전에 O5 의 선행 0(단계 직전 점검 — 계획 M4 「O5」)을 마칩니다.

1.  백엔드 값 파일을 만들고, `bucket` 자리표시자 `<STATE_BUCKET_PROD>` 를
    고정 이름 `pokeclip-tfstate-prod-apne2` 로 바꿉니다. 마지막 `grep` 은
    `bucket       = "pokeclip-tfstate-prod-apne2"` 한 줄을 내야 합니다.

    ```shell
    cp backend.hcl.example backend.hcl
    sed -i.orig 's/<STATE_BUCKET_PROD>/pokeclip-tfstate-prod-apne2/' backend.hcl
    rm backend.hcl.orig
    grep '^bucket' backend.hcl
    ```

1.  **로컬 상태로 생성합니다.** `backend.tf` 의 s3 백엔드를 덮는 override
    파일을 잠시 둡니다(Terraform 표준 override 파일 · 추적하지 않음).

    ```shell
    printf 'terraform {\n  backend "local" {}\n}\n' > backend_override.tf
    terraform init
    terraform plan -out=bootstrap.tfplan
    ```

    plan 이 생성 7 · 그 밖 0 이고, 자원마다 `tags_all` 에 네 키가 있는지
    확인한 뒤 적용합니다.

    ```shell
    terraform apply bootstrap.tfplan
    ```

1.  **자기 버킷으로 이관합니다.** override 파일을 지우고 s3 백엔드로
    다시 init 하면서 상태를 옮깁니다. 아래 블록은 이관
    (`init -migrate-state`)이 성공하고 확인 plan 이 「변경 없음」
    (`-detailed-exitcode` 종료 0)일 때만 로컬 상태를 지웁니다. plan 에 변경이
    있거나(종료 2) 이관 · plan 이 실패하면(종료 1) 지우지 않고
    `멈춤: 종료 <코드>` 줄을 낸 뒤 끝납니다 — 그 자리에서 멈추고 원인을
    봅니다. 블록을 통째로 붙여 넣어도 되고, 셸은 닫히지 않습니다.

    ```shell
    rm backend_override.tf
    if terraform init -migrate-state -backend-config=backend.hcl \
      && terraform plan -lock=false -detailed-exitcode; then
      rm -f terraform.tfstate terraform.tfstate.backup bootstrap.tfplan
    else
      printf '멈춤: 종료 %s — 로컬 상태를 지우지 않았습니다\n' "$?"
    fi
    ```

    `terraform.tfstate.backup` 은 없을 수 있어 `rm -f` 로 지웁니다. 다시 이관할
    때 첫 줄의 `rm` 이 「No such file」을 내는 것은 정상입니다(이미 지운 파일).

로컬 `terraform.tfstate` 는 이관이 끝나고 「No changes」를 본 뒤에 지웁니다.
이관(`-migrate-state`)이나 확인 plan 이 실패하면 로컬 `terraform.tfstate` ·
`terraform.tfstate.backup` 을 지우지 않고 그대로 둡니다 — 버킷을 만든 유일한
기록이고 평문 상태이므로 공유 위치로 옮기지 말고, 원인을 고쳐 다시 이관한
뒤에 지웁니다.

apply 가 SCP 거부(`AccessDenied` · 「explicit deny in a service control
policy」)로 실패하면 다시 시도하지 않고 멈춥니다(계획 3절 「SCP 멈춤 규칙」).

## 출력

| 출력 | 쓰는 곳 |
|---|---|
| `state_bucket_name` | prod 각 루트 `backend.hcl` 의 `bucket` |
| `state_bucket_arn` | 참고용 |
