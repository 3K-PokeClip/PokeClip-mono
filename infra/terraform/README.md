# infra/terraform

PokeClip AWS 엣지 인프라(HTTPS · CloudFront · ALB · NLB · Media 상자)의
Terraform 코드입니다. 설계 정본은 「HTTPS · 엣지 인프라 설계서」 3절입니다.

용어:

*   **루트(root)**: `terraform apply` 를 한 번 실행하는 디렉터리입니다.
    상태 파일을 하나 가집니다.
*   **모듈(module)**: 여러 루트가 가져다 쓰는 부품입니다. 혼자서는
    apply 하지 않습니다.

## 디렉터리

루트 하나가 상태 하나입니다. 의존은 위에서 아래 한쪽으로만 흐릅니다.

```
infra/terraform/
├── modules/                       # 루트들이 쓰는 부품
├── scripts/                       # guard.sh(금지 규약 검사) · guard_test.sh
├── mgmt/
│   ├── bootstrap/                 # ① 상태 버킷
│   └── org-extras/                # ② SCP · 권한 세트 할당 · Budgets · 드리프트 EventBridge
├── nonprod/
│   ├── bootstrap/                 # ① 상태 버킷
│   ├── dev/                       # ② dev EC2 · EIP · SG import(무변경) — 출력 dev_eip
│   ├── dns-legacy/                # ③ 옛 존 import(무변경) — 존 이전 때 NS TTL 하향 · 끝나면 삭제
│   └── dns-dev/                   # ④ dev 자식 존 + apex A + 옛 존의 dev 하위 레코드
└── prod/
    ├── bootstrap/                 # ① 상태 버킷
    ├── secrets/                   # ② CloudFront 공개키 · 엣지 비밀 그릇(값 없음)
    ├── dns/                       # ③ 새 존 · 이전용 레코드 복사본 · dev 위임 NS
    ├── network/                   # ④ VPC · 서브넷 · NAT · S3 Gateway EP
    ├── certs/                     # ⑥ ACM 서울 · us-east-1
    ├── registry/                  # ⑦ ECR · CI 푸시 역할 · apex 업로드 역할
    ├── platform/                  # ⑧ 공개 ALB · WAF · 내부 ALB · NLB · ECS
    ├── media/                     # ⑨ Media EC2 · 인스턴스 역할 · Media SG
    ├── edge/                      # ⑩ CloudFront 배포 · WAF · KVS · CF Function
    └── records/                   # ⑫ 운영 별칭 레코드
```

⑤ · ⑪ 은 prod 런타임 트랙(별도)이 소유합니다. Control Tower 가 만드는
것(조직 · OU · 계정)은 이 트리가 소유하지 않습니다. `infra/dev-media/` 는
이 트리 밖이며 건드리지 않습니다.

## 규약

### 버전 고정

*   Terraform `>= 1.11.0`(S3 백엔드 `use_lockfile` GA).
*   aws provider `>= 6.19.0`.
*   루트는 `.terraform.lock.hcl` 을 추적합니다.

### 백엔드 규약

*   각 루트의 `backend.tf` 에는 `terraform { backend "s3" {} }` 만 둡니다.
*   실제 값(버킷 · 키 · 리전)은 추적하지 않는 `backend.hcl` 에 둡니다.
    추적하는 것은 자리표시자만 든 `backend.hcl.example` 입니다.
*   잠금은 `use_lockfile = true`(S3 잠금 파일)입니다. DynamoDB 는 쓰지
    않습니다.
*   `init -backend-config=backend.hcl` 은 운영 단계(kty 승인)에서만
    실행합니다. 커밋 단계 검증은 `init -backend=false` 입니다.

### 실제 값은 추적하지 않는다

*   계정 ID · 인스턴스 ID · 리소스 ID · 사설 IP 는 `*.tfvars` 에 둡니다.
    `*.tfvars` 는 추적하지 않고 `terraform.tfvars.example` 의
    자리표시자(`<INSTANCE_ID_DEV>` 같은 꼴)만 추적합니다.

### 보안 그룹 규칙은 별도 자원만

*   SG 규칙은 `aws_vpc_security_group_ingress_rule` ·
    `aws_vpc_security_group_egress_rule` 로만 만듭니다.
*   `aws_security_group` 안의 인라인 `ingress` · `egress` 블록은 쓰지
    않습니다(guard R3).
*   Terraform 이 만든 SG 에는 기본 이그레스가 없으므로, 필요한 이그레스도
    별도 규칙으로 적습니다.

### 비밀 값은 상태에 남기지 않는다

*   `aws_secretsmanager_secret_version`(자원 · data source)과
    `aws_cloudfrontkeyvaluestore_key*` 는 쓰지 않습니다(guard R1 · R2).
*   비밀은 그릇(`aws_secretsmanager_secret`)만 만들고, 값은 운영 단계에서
    Terraform 밖으로 넣습니다.

## 검증

커밋마다 아래를 돌립니다. AWS 자격 증명이 필요 없습니다.

```
terraform fmt -check -recursive infra/terraform
terraform -chdir=<루트·모듈> init -backend=false -input=false
terraform -chdir=<루트·모듈> validate
terraform -chdir=<tests/ 가 있는 루트·모듈> test
bash infra/terraform/scripts/guard_test.sh
bash infra/terraform/scripts/guard.sh
shellcheck infra/terraform/scripts/*.sh
```

### 단언 방식

`terraform test` 는 `mock_provider` 로 AWS 에 접속하지 않고 plan 을 만든 뒤
`assert` 로 검사합니다. 테스트 상태는 늘 빈 상태에서 시작하므로 「변경 0」
같은 변경 action 은 단언할 수 없습니다. 검증 수단은 다음과 같이 나눕니다.

| 방식 | 무엇을 보나 |
|---|---|
| 속성 assert | 선언된 자원 · 출력의 속성. 정책은 `jsondecode` 로 비교 |
| data source 입력 인자 | data source 에 넘긴 인자(mock 계산 속성은 임의 값) |
| override_data · override_resource | remote_state · 계산 속성을 고정한 뒤 비교 |
| 운영 plan 확인 | 승인된 실제 plan. 무변경 · 변경 범위는 이것으로만 판정 |
| guard.sh | 금지 자원 · 자원 부재 · 인라인 SG · prevent_destroy |

## guard 규칙

`scripts/guard.sh` 는 트리 전체를 텍스트로 검사합니다. 검사 전에 주석
(`#` · `//` · `/* */`)과 heredoc 본문을 지우므로, 주석 속 예시는 위반도
통과 근거도 되지 않습니다. 규칙마다
`scripts/testdata/guard/bad_<규칙>[_<변형>]/` fixture 가 있고, `guard_test.sh`
가 「bad 는 그 규칙으로 실패 · `ok/` 는 통과 · `err_*/` 와 읽을 수 없는 파일은
종료 코드 2」를 먼저 확인합니다. 경로를 겨누는 규칙의 fixture 는 그 경로를
fixture 트리 안에 그대로 둡니다.

| 규칙 | 대상 경로 | 실패 조건 |
|---|---|---|
| R0 | 전체 | `*.tf.json` 파일(이 트리는 HCL 만 — guard 가 JSON 을 읽지 못함) |
| R1 | 전체 | `resource` · `data` `aws_secretsmanager_secret_version` |
| R2 | 전체 | `aws_cloudfrontkeyvaluestore_key*` |
| R3 | 전체 | `aws_security_group` 블록 바로 안 `ingress {` · `egress {` · `ingress =` · `egress =` |
| R4 | `modules/state_bucket` | `aws_s3_bucket` 자원 없음, 또는 그 자원의 `lifecycle` 블록에 `prevent_destroy = true` 없음 |
| R5 | `nonprod/dev` | `aws_instance` · `aws_eip` · `aws_security_group` 중 없는 유형이 있음, 또는 그 자원의 `lifecycle` 블록에 `prevent_destroy = true` 없음 |

검사를 끝낼 수 없으면(검사할 `.tf` 0건 · 읽을 수 없는 파일 · grep 오류)
위반 0 으로 넘어가지 않고 종료 코드 2 로 멈춥니다. R4 · R5 는 대상 경로가
없는 트리(다른 규칙의 fixture)에서는 건너뜁니다.

`prevent_destroy` 는 `terraform test`(mock)로 관찰할 수 없어 R4 · R5 가
맡습니다. dev SG 규칙 자원은 M12 가 8082 규칙을 지우므로 R5 대상이
아닙니다. dev 상자를 없애는 PR 은 R5 를 함께 지웁니다.

R5 이후 규칙은 그 규칙이 지키는 루트 · 모듈과 같은 PR 에서 더합니다.

한계:

*   텍스트 검사입니다. 다른 이름 · `dynamic` 블록 · 동적 생성 · 한 줄에
    여러 선언을 몰아 쓴 꼴로 우회하는 것은 막지 못합니다. 그것은 리뷰가
    막습니다.
*   R3 · R4 는 중괄호 수로 블록 끝을 찾습니다. 주석 · heredoc · 한 줄
    문자열 속 중괄호는 세지 않지만, 문자열 보간(`${...}`) 안에 다시 따옴표를
    넣은 꼴은 구분하지 못합니다.
