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
├── policy/guard/                  # 금지 규약 Rego 정책(conftest) · 단위 시험
├── scripts/                       # guard.sh(금지 규약 검사 실행기) · guard_test.sh
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

### 상태 읽기 권한은 비밀 열람 권한이다

*   상태 파일에는 자원 속성이 평문으로 남습니다(SG · EIP · DNS 값, 이후
    단계의 민감 속성). 상태 버킷 · 키를 읽을 수 있는 주체는 그 값을 모두
    읽을 수 있습니다.
*   그래서 상태 읽기 권한은 비밀 열람 권한과 같은 무게로 줍니다. IAM 은
    루트별 키 접두사(`<루트>/`)로 나눠, 한 루트를 다루는 역할이 다른
    루트의 상태를 읽지 못하게 합니다.
*   상태 버킷은 평문 HTTP · TLS 1.2 미만 요청을 정책으로 거부하고, ACL 을
    끄고, 이전 버전을 90일 뒤 지우되 최근 이전 버전 5개는 남깁니다
    (`modules/state_bucket`).

### 운영 O1 전 확인

import 루트(`nonprod/dev` · `nonprod/dns-legacy`)의 선언은 실물에 대한
가정 위에 서 있습니다. 확인할 가정은 각 루트의 `terraform.tfvars.example`
머리에 있습니다. 읽기 조사 결과가 가정과 다르면 값만 바꾸지 말고 선언을
고칩니다. 특히:

*   dev SG 규칙은 단일 포트 · IPv4 CIDR 하나 꼴만 표현합니다. IPv6 ·
    접두사 목록 · 참조 SG 규칙은 표현하지 못합니다.
*   dns-legacy `records` 는 단순 레코드만 표현합니다. 별칭 · 라우팅 정책 ·
    헬스 체크 레코드는 표현하지 못합니다.

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
conftest verify --policy infra/terraform/policy/guard
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
| guard(conftest 정책) | 금지 자원 · 자원 부재 · 인라인 SG · prevent_destroy |

## guard 규칙

guard 는 Terraform 트리의 금지 규약을 검사합니다. 규칙 R1 ~ R11 은
[conftest](https://www.conftest.dev/)(`--parser hcl2`)로 돌리는 Rego 정책
`policy/guard/*.rego` 가 판정합니다. `scripts/guard.sh` 는 얇은 실행기입니다.
파서가 볼 수 없는 R0 를 직접 보고, 파일마다 파싱이 되는지 먼저 가린 뒤, 트리
전체를 한 입력으로 묶어(`--combine`) 정책을 돌립니다. HCL 을 파서로 읽으므로
주석 · heredoc · 문자열 속 글자는 선언으로 보이지 않습니다.

예전 guard 는 awk 로 HCL 을 글자 단위로 읽었는데, M1 PR 리뷰 다섯 라운드 내내
새 우회 꼴이 나와 파서 기반으로 바꿨습니다(볼트 결정 42).

| 규칙 | 대상 경로 | 실패 조건 |
|---|---|---|
| R0 | 전체 | `*.tf.json` 파일 · 심볼릭 링크(파일이든 디렉터리든 — guard.sh 가 셸로 검사) |
| R1 | 전체 | `resource` · `data` `aws_secretsmanager_secret_version`(`count = 0` 이어도) |
| R2 | 전체 | `resource` · `data` 유형이 `aws_cloudfrontkeyvaluestore_key` 로 시작 |
| R3 | 전체 | `aws_security_group` 자원 안 `ingress` · `egress`(블록 · 속성 · `dynamic "ingress"` · `dynamic "egress"`) |
| R4 | `modules/state_bucket/` | `aws_s3_bucket` 자원 없음, 또는 그 자원 중 `lifecycle { prevent_destroy = true }`(리터럴 `true`)가 없는 것 |
| R11 | `nonprod/dev/` | `aws_instance` · `aws_eip` · `aws_security_group` 중 없는 유형이 있음, 또는 그 자원 중 리터럴 `prevent_destroy = true` 가 없는 것 |

`prevent_destroy` 는 `terraform test`(mock)로 관찰할 수 없어 R4 · R11 이
맡습니다. conftest 는 식을 평가하지 않으므로 리터럴 `true` 만 인정합니다 —
`true != true` · `var.keep` 같은 식과 문자열 `"true"` 는 위반입니다. R4 · R11 은
대상 경로 아래 `.tf` 가 하나도 없는 트리(다른 규칙의 fixture)에서는 건너뜁니다.
dev SG 규칙 자원은 M12 가 8082 규칙을 지우므로 R11 대상이 아닙니다. dev 상자를
없애는 PR 은 R11 을 함께 지웁니다.

R5 ~ R10 은 계획이 번호를 정해 둔 규칙이라, 그 규칙이 지키는 루트 · 모듈과
같은 PR 에서 그 번호로 `policy/guard/` 에 더합니다(정책 · `_test.rego` ·
fixture 를 함께).

### 설치와 실행

*   로컬: `brew install conftest`. CI 는 계획의 M-CI(infra-ci)에서 conftest
    0.71.1 을 체크섬 고정으로 내려받아 붙습니다. 그 전까지는 PR 마다 아래 셋을
    손으로 돌립니다.

```
conftest verify --policy infra/terraform/policy/guard   # 정책 단위 시험
bash infra/terraform/scripts/guard_test.sh              # fixture 끝에서 끝까지
bash infra/terraform/scripts/guard.sh                   # 실제 트리
```

*   `guard.sh [루트]` 는 루트로 옮겨 루트 기준 상대 경로로 검사합니다. 정책은
    루트와 상관없이 늘 이 저장소의 `policy/guard` 를 씁니다. 실제 트리의 하위
    디렉터리(`nonprod/dev` 등)를 루트로 주면 경로 규칙이 빠지므로 종료 2 입니다
    (fixture 트리 · 저장소 밖 트리는 허용).
*   셸에 남은 `CONFTEST_*` 환경 변수는 지우고 `--namespace main` 으로 돕니다 —
    다른 프로젝트 설정이 정책을 조용히 끄지 못하게 합니다.
*   위반은 한 줄에 하나씩 「guard: R<번호> <상대 경로>: <설명>」으로 나옵니다.

### 종료 코드

| 코드 | 뜻 |
|---|---|
| 0 | 위반 0 |
| 1 | 위반 있음(R0 포함) |
| 2 | 사용법 오류 · conftest 없음 · 하위 디렉터리 루트 · 검사 불가 — 파싱 실패 · 읽을 수 없는 파일 · 검사할 `.tf` 0건 · 실행된 규칙 0건 · conftest 자체 오류(정책 컴파일 · 내장 함수 오류 · 모르는 출력) |

검사를 끝낼 수 없으면 위반 0 으로 넘어가지 않고 2 로 멈춥니다(fail-closed).
파싱 실패가 하나라도 있으면 정책을 돌리지 않습니다. conftest 는 위반과 오류를
같은 종료 코드로 내므로, guard.sh 는 `--no-fail` 로 위반을 종료 코드에서 떼고
(그러면 0 이 아닌 종료는 모두 오류) 결과 줄의 FAIL 수를 요약 줄과 맞춰 봅니다.
요약의 검사 수가 0 이면(패키지 이름 · `deny` 철자 실수로 규칙이 하나도 안 돎)
위반 0 이 아니라 2 입니다.

### fixture

규칙마다 `scripts/testdata/guard/bad_<규칙>[_<변형>]/` fixture 가 있고,
`guard_test.sh` 가 「`conftest verify` 통과 · bad 는 그 규칙으로 종료 1 ·
`ok/` 는 통과 · `err_*/` 와 읽을 수 없는 파일 · conftest 없음은 종료 2」를
확인합니다. 나누는 기준은 Terraform 이 받는 코드인가입니다 — Terraform 이 받는
꼴은 `bad_` · `ok`, 받지 않는(파싱되지 않는) 꼴은 `err_` 입니다. 경로를 겨누는
규칙의 fixture 는 그 경로를 fixture 트리 안에 그대로 둡니다.
`terraform fmt -recursive` 를 깨는 fixture 는 `*.tf.in` 으로 두고
`guard_test.sh` 가 임시 디렉터리에 `*.tf` 로 복사해 돌립니다.

### 한계

*   정책은 **이 트리에 직접 쓴 선언**만 봅니다. 직접 선언한 자원에
    `count` · `for_each` 를 붙여도 잡지만, 다른 이름 · 외부 모듈 안에 숨긴
    선언 · 모듈 호출로 만드는 자원은 못 봅니다. 그것은 리뷰가 막습니다.
*   R4 · R11 은 대상 경로 아래 `.tf` 가 하나도 없으면 건너뜁니다(다른 규칙
    fixture 를 위해). 대상 디렉터리를 통째로 지우거나 옮기는 PR 은 규칙도 함께
    고쳐야 하며, 그것은 리뷰가 봅니다.
*   이름이 `.terraform` 인 디렉터리는 깊이와 상관없이 검사에서 뺍니다(provider
    캐시). 그 이름 아래에 구성을 두지 않습니다.
*   `PATH` 의 conftest 를 믿습니다. 판정이 conftest 출력 형식(`FAIL - Combined -
    main - ` 과 요약 줄)에 기대므로 버전을 바꾸면 모르는 줄로 종료 2 가 날 수
    있습니다(안전한 쪽) — CI 는 0.71.1 고정, 로컬 brew 판은 `conftest
    --version` 이 `dev` 로 나와 버전 문자열로는 확인되지 않습니다.
*   R0(심볼릭 링크 · `.tf.json`)는 정책이 아니라 guard.sh 의 셸 검사입니다.
    `--parser hcl2` 는 JSON 구성을 읽지 못하므로 `.tf.json` 은 이 트리에 두지
    않습니다.
*   R1 은 Secrets Manager 비밀 버전만 봅니다. `aws_ssm_parameter` 의
    SecureString 값도 상태에 평문으로 남지만 R1 대상이 아닙니다(설계상 SSM
    비밀은 없음 — 쓰게 되면 리뷰가 막고 규칙을 더합니다).
*   파일 첫머리 UTF-8 BOM 과 줄끝 CR(CRLF)은 conftest 의 hcl2 파서가 그대로
    읽습니다(Terraform 도 받음). BOM · CRLF 파일 안의 금지 선언도 잡힙니다
    (`bad_R1_bom` · `bad_R1_crlf`).
*   conftest 는 `count` 를 펼치지 않으므로 `count = 0` 인 금지 자원도
    위반입니다. 반대로 식을 평가하지 않으므로 「true 와 같은 뜻의 식」도 R4 ·
    R11 위반입니다(엄격한 쪽).
