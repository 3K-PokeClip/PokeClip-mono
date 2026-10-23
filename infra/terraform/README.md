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
`prod/*` · `nonprod/*` 는 같은 리스 계정 안의 환경입니다(설계 3-1).
아래는 **목표 구조**입니다 — 지금 있는 것은
`modules/{state_bucket,secret_shells,cloudfront_public_key}` ·
`modules/{network,acm_certificate}` · `policy/guard` · `scripts` · `runbooks` ·
`nonprod/{bootstrap,dev,dns-legacy}` ·
`prod/{bootstrap,secrets,network,certs,registry}` 뿐이고, 나머지 루트는 계획의
후속 PR 이 더합니다.

```
infra/terraform/
├── modules/                       # 루트들이 쓰는 부품
├── policy/guard/                  # 금지 규약 Rego 정책(conftest) · 단위 시험
├── scripts/                       # guard.sh(금지 규약 검사 실행기) · guard_test.sh · precheck/(단계 직전 점검)
│   ├── put-secret-from-file.sh    # prod 엣지 비밀 값 입력(runbooks/edge-secrets.md)
│   └── test/                      # put-secret-from-file.sh 시험 · 가짜 aws(fakebin/)
├── runbooks/                      # 운영 단계 절차(kty 승인) — org-ct.md 등
├── mgmt/                          # (보류 — 결정 46 · 재개 키트)
│   ├── bootstrap/                 # ① 상태 버킷
│   └── org-extras/                # ② SCP · 권한 세트 할당 · Budgets · 드리프트 EventBridge
├── nonprod/
│   ├── bootstrap/                 # ① 상태 버킷
│   ├── dev/                       # ② dev EC2 · EIP · SG import(무변경) — 출력 dev_eip
│   ├── dns-legacy/                # ③ 공개 존 영구 소유(리스 기간) — 존 prevent_destroy(R12)
│   └── dns-dev/                   # ④ dev 자식 존 + apex A + 옛 존의 dev 하위 레코드(보류 — 결정 46 · 재개 키트)
└── prod/
    ├── bootstrap/                 # ① 상태 버킷 pokeclip-tfstate-prod-apne2(같은 계정 안 별도 버킷)
    ├── secrets/                   # ② CloudFront 공개키 · 엣지 비밀 그릇 3개(값 · 리소스 정책 없음)
    ├── dns/                       # ③ 새 존 · 이전용 레코드 복사본 · dev 위임 NS(보류 — 결정 46 · 재개 키트)
    ├── network/                   # ④ VPC · 서브넷(2 AZ) · NAT 1개 · S3 Gateway EP
    ├── certs/                     # ⑥ ACM 서울 · us-east-1 + 검증 레코드 한 벌(존 = 이름 조회)
    ├── registry/                  # ⑦ ECR 11개(CI 역할은 쓰기로 할 때 — 40차)
    ├── platform/                  # ⑧ 공개 ALB · WAF · 내부 ALB · NLB · ECS
    ├── media/                     # ⑨ Media EC2 · 인스턴스 역할 · Media SG
    ├── edge/                      # ⑩ CloudFront 배포 · WAF · KVS · CF Function
    └── records/                   # ⑫ 운영 별칭 레코드
```

⑤ · ⑪ 은 prod 런타임 트랙(별도)이 소유합니다. 그 트랙과 주고받는 출력
이름 · 소유 경계는 [런타임 트랙 인터페이스 계약](CONTRACT-runtime.md)이
정본입니다. 운영 측(ISB)이 만든 것(SCP ·
OU · 운영 측 역할)과 기존 수동 자원(IAM 사용자 · dev 자동 주체 · GitHub OIDC
공급자)은 이 트리가 소유하지 않습니다. `infra/dev-media/` 는 이 트리 밖이며
건드리지 않습니다.

prod 엣지 비밀의 값 입력 · 교체 절차는 [엣지 비밀 런북](runbooks/edge-secrets.md)
입니다.

운영 단계 절차는 `runbooks/` 에 있습니다 —
[조직 · CT 도입(보류 — 결정 46)](runbooks/org-ct.md) ·
[prod 산출물 올리기(이미지 푸시 · 랜딩 업로드)](runbooks/prod-artifacts.md).
운영 단계마다 먼저 [단계 직전 점검](runbooks/stage-precheck.md)을 합니다.
2-3 판정(dev 자동 주체 → prod 권한 없음, 정책 시뮬레이터)은
`scripts/precheck/principal-check.sh` 입니다.

## 규약

### 버전 고정

*   Terraform `>= 1.11.0`(S3 백엔드 `use_lockfile` GA).
*   aws provider `>= 6.19.0`.
*   루트는 `.terraform.lock.hcl` 을 추적합니다(`modules/` 는 면제 —
    「검증」 절).

### 백엔드 규약

*   각 루트의 `backend.tf` 에는 `terraform { backend "s3" {} }` 만 둡니다.
*   실제 값(버킷 · 키 · 리전)은 추적하지 않는 `backend.hcl` 에 둡니다.
    추적하는 것은 자리표시자만 든 `backend.hcl.example` 입니다.
*   잠금은 `use_lockfile = true`(S3 잠금 파일)입니다. DynamoDB 는 쓰지
    않습니다.
*   `init -backend-config=backend.hcl` 은 운영 단계(kty 승인)에서만
    실행합니다. 커밋 단계 검증은 `init -backend=false` 입니다.

### 실제 값은 추적하지 않는다

*   계정 ID 는 `data.aws_caller_identity` 로 얻고 tfvars · 코드 리터럴에 두지
    않습니다(R13). 인스턴스 ID · 리소스 ID · 사설 IP 는 `*.tfvars` 에 둡니다.
    `*.tfvars` 는 추적하지 않고 `terraform.tfvars.example` 의
    자리표시자(`<INSTANCE_ID_DEV>` 같은 꼴)만 추적합니다.

### 상태 읽기 권한은 비밀 열람 권한이다

*   상태 파일에는 자원 속성이 평문으로 남습니다(SG · EIP · DNS 값, dev EC2 의
    `user_data` — 지금은 `ignore_changes` 로 선언만 비켜 갈 뿐 import 하면 현재
    값이 상태에 들어갑니다, 이후 단계의 민감 속성). 상태 버킷 · 키를 읽을 수 있는 주체는 그 값을 모두
    읽을 수 있습니다.
*   그래서 상태 읽기 권한은 비밀 열람 권한과 같은 무게로 줍니다. **계획(후속
    M — 이 PR 에는 IAM 이 없습니다):** IAM 을 루트별 키 접두사(`<루트>/`)로
    나눠, 한 루트를 다루는 역할이 다른 루트의 상태를 읽지 못하게 합니다.
*   상태 버킷은 평문 HTTP · TLS 1.2 미만 요청을 정책으로 거부하고, ACL 을
    끄고, 이전 버전을 90일 뒤 지우되 최근 이전 버전 5개는 남기고, 이전 버전이
    다 지워진 삭제 표지는 정리합니다. 한계: 「최근 5개 보존」이 잠금 파일
    (`.tflock`)에도 걸려 그 이전 버전 · 삭제 표지는 몇 개씩 남습니다 — S3
    수명주기는 접미사로 대상을 고를 수 없어 따로 두지 않았고, 크기가 작아
    비용 영향은 미미합니다
    (`modules/state_bucket`).

### 운영 O1 전 확인

import 루트(`nonprod/dev` · `nonprod/dns-legacy`)의 선언은 실물에 대한
가정 위에 서 있습니다. 확인할 가정은 각 루트의 `terraform.tfvars.example`
머리에 있습니다. 읽기 조사 결과가 가정과 다르면 값만 바꾸지 말고 선언을
고칩니다. 특히:

*   dev SG 규칙은 단일 포트 · IPv4 CIDR 하나 꼴만 표현합니다. IPv6 ·
    접두사 목록 · 참조 SG 규칙은 표현하지 못합니다.
*   dev EC2 의 인스턴스 프로필은 현재 이름을 변수로 선언해 연결을 코드에
    고정합니다(O1 확인 9번). provider 에서 이 인자는 Optional + Computed 라
    생략해도 현재 연결은 유지되지만, 빈 값은 연결을 풀고(SSM 접속 · 권한
    단절) 다른 이름은 연결을 바꿉니다. 프로필 · IAM 역할 자체는 import 하지
    않고 이 트리가 관리하지 않으므로, **이 값을 바꾸는 변경은 권한 변경으로
    보고 리뷰합니다**(바뀐 프로필의 권한은 이 저장소 diff 에 보이지 않음).
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
*   비밀 그릇 모듈(`modules/secret_shells`)에는 리소스 정책(별도 자원 ·
    인라인 `policy` 인자)과 `names` 밖의 입력을 두지 않습니다(guard R6). 읽기
    권한은 읽는 쪽 루트가 자기 역할 정책으로 붙입니다.
*   prod 값은 `scripts/put-secret-from-file.sh` 로 파일에서 넣습니다 — 값이
    명령 인자 · 환경 변수 · 임시 파일 · 출력에 남지 않습니다(절차 ·
    종료 코드는 [엣지 비밀 런북](runbooks/edge-secrets.md)).

## 검증

커밋마다 아래를 돌립니다. AWS 자격 증명이 필요 없습니다.

```
terraform fmt -check -recursive infra/terraform
terraform -chdir=<루트·모듈> init -backend=false -input=false
terraform -chdir=<루트·모듈> validate
terraform -chdir=<tests/ 가 있는 루트·모듈> test
conftest verify -c infra/terraform/scripts/guard-data/empty-config.toml \
  --policy infra/terraform/policy/guard --namespace main
bash infra/terraform/scripts/guard_test.sh
bash infra/terraform/scripts/guard.sh
bash infra/terraform/scripts/test/put_secret_test.sh
shellcheck infra/terraform/scripts/test/*.sh \
  infra/terraform/scripts/test/fakebin/aws
for d in infra/terraform/modules/*/ infra/terraform/nonprod/*/ \
  infra/terraform/prod/*/; do tflint --chdir="$d" || printf 'FAIL %s\n' "$d"; done
shellcheck infra/terraform/scripts/*.sh
for t in infra/terraform/scripts/precheck/tests/*_test.sh; do bash "$t" || printf 'FAIL %s\n' "$t"; done
shellcheck -x infra/terraform/scripts/precheck/*.sh \
  infra/terraform/scripts/precheck/lib/*.sh \
  infra/terraform/scripts/precheck/tests/*.sh \
  infra/terraform/scripts/precheck/tests/fakebin/aws
```

tflint(`brew install terraform-linters/tap/tflint` · 설정 파일 없음 — 기본
terraform 규칙 묶음)는 루트 · 모듈마다 돌립니다. `--recursive` 는
`scripts/testdata` 의 guard fixture 까지 읽어 늘 실패합니다.
`put_secret_test.sh` 는 PATH 에 진짜 aws 가 있으면
닫힌 로컬 주소(`127.0.0.1:9`) · 가짜 자격으로 실물 CLI 계약 사례를 돌리고(AWS
에 닿지 않음), 없으면 skip 으로 셉니다.

`put_secret_test.sh` 의 정적 검사(`no-file-writes` · `no-external-commands` ·
`no-endpoint-override` · `no-heredoc`)는 흔한 회귀를 잡는 울타리입니다. 일부러
숨긴 꼴(문자열 안 명령 치환 · 공백 변형 · 리디렉션 없는 쓰기 내장 명령 등)은
PR 리뷰가 막습니다 — 실제 누출 방어는 실행 시험(실물 CLI 계약 · 잔여 검사 ·
출력 완전 일치)입니다.

`infra/terraform/**` · `infra/media-host/**` 를 고친 PR 에서는
`.github/workflows/infra-ci.yml`(infra-ci)이 위 명령을 모두 돌립니다.
도구(terraform 1.16.4 · tflint 0.64.0 · conftest 0.71.1 · shellcheck
0.11.0)는 sha256 을 고정해 내려받습니다. 필수 체크가 아니므로 머지 전에 PR
화면의 체크 목록에서 infra-ci 의 세 체크(terraform · guard · shell)가
초록인지 확인합니다. **체크가 목록에 없으면 통과가 아니라 돌지 않은
것입니다** — 위 명령을 손으로 돌리고 결과를 PR 에 적습니다. CI 의 한계는
다음과 같습니다.

*   변경 파일이 3,000개를 넘는 PR 에서는 paths 필터가 뒤쪽 파일을 보지
    못해 워크플로가 아예 돌지 않을 수 있습니다. 그런 PR 은 쪼개거나 위
    명령을 손으로 돌립니다.
*   저장소가 공개라 CI 로그도 공개입니다. 시험에는 가짜 계정 ID · 가짜
    이름만 씁니다.
*   두 시험이 러너 이미지의 AWS CLI 에 기댑니다. `put_secret_test.sh` 의
    실물 CLI 계약 사례(위 닫힌 로컬 주소 · 가짜 자격으로만 부름)와 precheck
    `testlib_test.sh` 의 실행 비트 사례(PATH 에 실행 가능한 aws 가 따로
    있어야 뜻이 있음 — fakebin · init-message)입니다. 러너 라벨
    `ubuntu-24.04` 는 OS 판만 고정하므로, 러너 이미지가 갱신되면 AWS CLI
    판이 바뀔 수 있습니다(그 실행의 판은 Set up job 로그 「Runner Image」).
    CI 는 `scripts/test/` 시험이 하나라도 skip 하면 실패로 봅니다. 러너에서
    AWS CLI 가 빠지면 shell 잡의 「단계 직전 점검 시험」 이 먼저 빨개지고,
    뒤의 「스크립트 시험」(`put_secret_test.sh` 의 skip 판정 포함)과 그 뒤
    단계는 건너뜁니다.
*   러너의 bash 는 5.2 입니다. 스크립트가 지키는 macOS bash 3.2 제약은
    로컬에서 돌려야 드러납니다.
*   guard_test 의 `config-first-folded` 사례(ſ 를 s 로 접는 파일 시스템)는
    러너(ext4)에서 건너뜁니다. APFS 에서 도는 로컬이 맡습니다.
*   `modules/` 는 lock 면제입니다(`.gitignore` 가 모듈의 lock 을 뺍니다).
    그래서 CI 에서 aws provider 최신판으로 돌고, 쓰인 판은 init 로그에
    남습니다. 그 밖의 루트는 lock 이 없으면 CI 가 실패합니다.
*   CI 는 루트를 `-lockfile=readonly` 로 init 합니다. 루트에 provider 를 새로
    더하면 로컬 `terraform init` 은 lock 에 조용히 적어 넣지만, CI 는 lock 을
    고쳐 쓰지 않으므로 실패합니다 — 갱신된 lock 을 함께 올립니다. 잠긴 판을
    그대로 만족하는 제약 변경은 양쪽 모두 통과하고, 잠긴 판을 벗어나는 제약은
    로컬에서도 `init -upgrade` 가 필요합니다.

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

guard 는 Terraform 트리의 금지 규약을 검사합니다. 규칙 R1 ~ R13 은
[conftest](https://www.conftest.dev/)(`--parser hcl2`)로 돌리는 Rego 정책
`policy/guard/*.rego` 가 판정합니다. `scripts/guard.sh` 는 얇은 실행기입니다.
파서가 볼 수 없는 R0 를 직접 보고, 파일마다 파싱이 되는지 먼저 가린 뒤, 트리
전체를 한 입력으로 묶어(`--combine`) 정책을 돌립니다. HCL 을 파서로 읽으므로
주석 · heredoc · 문자열 속 글자는 선언으로 보이지 않습니다.

예전 guard 는 awk 로 HCL 을 글자 단위로 읽었는데, M1 PR 리뷰 다섯 라운드 내내
새 우회 꼴이 나와 파서 기반으로 바꿨습니다(볼트 결정 42).

| 규칙 | 대상 경로 | 실패 조건 |
|---|---|---|
| R0 | 전체 | `*.tf.json` 파일 · 심볼릭 링크(파일이든 디렉터리든 — 이름이 `.terraform` · `scripts/testdata` 여도) · 숨김 `.tf`(이름이 `.` 으로 시작 — Terraform 은 읽지 않음) · 경로에 줄바꿈 · 제어 문자가 든 항목 · conftest 설정 파일 `conftest.<확장자>`(viper 12종 · 대소문자 무관 · 링크여도 — 있으면 conftest 를 부르지 않고 종료 1). guard.sh 가 셸로 검사 |
| R1 | 전체 | `resource` · `data` `aws_secretsmanager_secret_version`(`count = 0` 이어도) |
| R2 | 전체 | `resource` · `data` 유형이 `aws_cloudfrontkeyvaluestore_key` 로 시작 |
| R3 | 전체 | `aws_security_group` 자원 안 `ingress` · `egress`(블록 · 속성 · `dynamic "ingress"` · `dynamic "egress"`) |
| R4 | `modules/state_bucket/` | `aws_s3_bucket` 자원 없음, 또는 그 자원 중 `lifecycle { prevent_destroy = true }`(리터럴 `true`)가 없는 것 |
| R6 | `modules/secret_shells/` | `aws_secretsmanager_secret_policy` 선언(`count = 0` 이어도) · `aws_secretsmanager_secret` 자원의 인라인 `policy` 인자(값과 무관 — kty 61차) · 이름이 `names` 가 아닌 `variable`(읽기 역할 등 다른 입력). 대상 경로에 `.tf` 가 없으면 건너뜀. 모듈 밖(다른 루트가 출력 ARN 으로 비밀에 정책을 붙이는 것)은 보지 않음 — 리뷰가 막음 |
| R11 | `nonprod/dev/` | `aws_instance` · `aws_eip` · `aws_security_group` 중 없는 유형이 있음, 또는 그 자원 중 리터럴 `prevent_destroy = true` 가 없는 것 |
| R12 | `nonprod/dns-legacy/` | 운영 트리: ① 그 루트 바로 아래 `.tf` 없음 ② 주소가 정확히 `aws_route53_zone.legacy` 인 선언 없음 ③ 그 루트의 `aws_route53_zone` 중 리터럴 `prevent_destroy = true` 가 없는 것. fixture 트리는 ① · ② 의 부재만 건너뜀(③ 은 그대로) |
| R13 | 전체(`.tf` 만 — `*.example` · 시험 파일 · 주석은 리뷰) | 문자열 · 숫자 리터럴(맵 키 포함) 안의 계정 ID 꼴 숫자열 — 12자리 · 4-4-4 대시 표기(앞뒤가 숫자가 아님 — ARN 속 계정 ID 포함) · 이름을 소문자로 바꾸고 `-` · `_` 를 뺐을 때 `accountid` 가 드는 `variable`(`accountId` · `account-id` 포함). 위반 메시지는 그 숫자열을 가림 |

`prevent_destroy` 는 `terraform test`(mock)로 관찰할 수 없어 R4 · R11 · R12 가
맡습니다. conftest 는 식을 평가하지 않으므로 리터럴 `true` 만 인정합니다 —
`true != true` · `var.keep` 같은 식과 문자열 `"true"` 는 위반입니다. R4 · R11 은
대상 경로 아래 `.tf` 가 하나도 없는 트리(다른 규칙의 fixture)에서는 건너뜁니다.
dev SG 규칙 자원은 M12 가 8082 규칙을 지우므로 R11 대상이 아닙니다. dev 상자를
없애는 PR 은 R11 을 함께 지웁니다.

**R12 는 운영 트리에서 건너뛰지 않습니다.** 공개 존 루트의 파일을 지우거나
옮기면 `prevent_destroy` 도 함께 사라져 아무것도 막지 못하기 때문입니다(설계
3-1). 운영 트리와 fixture 트리를 가르는 표지(`mode`)는 guard.sh 가 루트를 보고
골라 `scripts/guard-data/<표지>/mode.json` 을 늘 `--data` 로 넘깁니다. 루트가
`scripts/testdata/` 아래일 때만 `fixture` 이고, 실제 트리 · 저장소 밖 트리는
`operational` 입니다. 정책은 표지가 정확히 `fixture` 일 때만 ① · ② 의 부재를
건너뛰고, 표지가 없거나 모르는 값이면 운영 트리로 봅니다. 루트를 옮기거나 존
주소를 바꾸는 PR 은 R12 도 같은 PR 에서 고쳐야 통과합니다.

R8 ~ R10 은 계획이 번호를 정해 둔 규칙이라, 그 규칙이 지키는 루트 ·
모듈과 같은 PR 에서 그 번호로 `policy/guard/` 에 더합니다(정책 · `_test.rego` ·
fixture 를 함께). R5 · R7 은 **보류**입니다 — R5 는 재개 키트(결정 46), R7 은
CI 자동 푸시를 켜는 후속 PR(40차)이 더합니다.

### 설치와 실행

*   로컬: `brew install conftest`. CI(infra-ci)는 conftest 0.71.1 을 sha256
    고정으로 내려받아 이 트리를 고친 PR 마다 아래 셋을 돌립니다(「검증」
    절).

```
conftest verify -c infra/terraform/scripts/guard-data/empty-config.toml \
  --policy infra/terraform/policy/guard \
  --namespace main                                      # 정책 단위 시험
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
규칙의 fixture 는 그 경로를 fixture 트리 안에 그대로 둡니다. R12 의 운영 표지
사례는 fixture 를 저장소 밖 임시 디렉터리에 복사해 돌립니다 — `ok_R12/` 는 두
표지 모두 통과하고, `op_<규칙>_<변형>/` 은 그 저장소 밖 사본으로만 돌립니다.
열거 경계(숨김 · 줄바꿈 · 제어 문자 이름 · 링크)와 conftest 설정 파일 사례는
저장소에 두지 않고, `guard_test.sh` 가 `ok_R12/` 의 저장소 밖 사본에 만들어
돌립니다.
`terraform fmt -recursive` 를 깨는 fixture 는 `*.tf.in` 으로 두고
`guard_test.sh` 가 임시 디렉터리에 `*.tf` 로 복사해 돌립니다.

### 한계

*   정책은 **이 트리에 직접 쓴 선언**만 봅니다. 직접 선언한 자원에
    `count` · `for_each` 를 붙여도 잡지만, 다른 이름 · 외부 모듈 안에 숨긴
    선언 · 모듈 호출로 만드는 자원은 못 봅니다. 그것은 리뷰가 막습니다.
*   R4 · R11 은 대상 경로 아래 `.tf` 가 하나도 없으면 건너뜁니다(다른 규칙
    fixture 를 위해). 대상 디렉터리를 통째로 지우거나 옮기는 PR 은 규칙도 함께
    고쳐야 하며, 그것은 리뷰가 봅니다.
*   이름이 `.terraform` 인 **디렉터리**는 깊이와 상관없이 검사에서
    뺍니다(provider 캐시). 그 이름 아래에 구성을 두지 않습니다. 같은 이름의
    링크는 R0 입니다.
*   `scripts/testdata` · `.terraform` 아래의 모듈을 module `source` 로 쓰면
    Terraform 은 읽지만 guard 는 그 모듈을 보지 못합니다(검사에서 빼는
    디렉터리) — 리뷰 몫입니다.
*   실제 트리보다 **상위** 디렉터리(`.` · `infra` 등)를 루트로 주면 경로 규칙
    (R4 · R11 · R12)이 맞지 않고 `scripts/testdata` 도 빠지지 않아 fixture 위반이
    쏟아집니다(종료 1 — 위반 0 으로 새지는 않음). 루트는 주지 않거나
    `infra/terraform` 으로 줍니다.
*   모든 심볼릭 링크가 R0 입니다(`.tf` 가 아닌 문서 링크도, 이름이
    `.terraform` 이어도).
*   `.gitignore` 가 막는 로컬 파일(`override.tf` · `*_override.tf` 등)은 로컬
    guard 는 보지만 CI 는 볼 수 없습니다 — 커밋되지 않으므로 리뷰 대상도
    아닙니다.
*   `PATH` 의 conftest 를 믿습니다. `PATH` 에 `.` 이나 빈 항목을 두지 않습니다
    (루트로 이동한 뒤 그 트리의 `conftest` 가 실행될 수 있음). 판정이
    conftest 출력 형식(`FAIL - Combined - main - ` 과 요약 줄)에 기대므로 버전을 바꾸면 모르는 줄로 종료 2 가 날 수
    있습니다(안전한 쪽) — CI 는 0.71.1 고정, 로컬 brew 판은 `conftest
    --version` 이 `dev` 로 나와 버전 문자열로는 확인되지 않습니다.
*   R3 은 `aws_security_group` 만 봅니다. 기본 SG(`aws_default_security_group`)
    의 인라인 규칙은 대상이 아닙니다 — 기본 SG 를 Terraform 으로 다루게 되면
    리뷰가 보고 규칙을 더합니다.
*   검사 트리 안의 conftest 설정 파일 `conftest.<확장자>`(viper 가 찾는 12종,
    대소문자 무관)는 일반 파일이든 링크든 R0 위반입니다. conftest 0.71.1 은
    검사 루트의 그 파일을 읽습니다 — json · toml · yaml · yml · dotenv · env
    6종은 읽어 적용하고(namespace · data 를 바꾸고 `update` 로 정책을
    내려받기까지 함), properties · props · prop · hcl · tfvars · ini 6종은
    읽다가 오류로 멈춥니다. 어느 쪽이든 R0 이고, 하나라도 있으면 guard.sh 는
    conftest 를 한 번도 부르지 않고 종료 1 로 멈춥니다. `conftest.md` 처럼
    conftest 가 읽지 않는 이름은 대상이 아닙니다.
*   겹으로, guard.sh 와 guard_test.sh 는 conftest 를 부를 때마다 저장소에
    고정한 빈 설정(`scripts/guard-data/empty-config.toml`)을 `--config-file`
    로 넘깁니다. 그래서 conftest 는 검사 루트나 호출한 디렉터리의 설정을 아예
    찾지 않습니다.
*   R0(심볼릭 링크 · `.tf.json` · 숨김 · 줄바꿈 · 제어 문자 이름 · conftest
    설정 파일)는 정책이 아니라 guard.sh 의 셸 검사입니다. 파일 목록은 NUL 로
    구분해 읽으므로 이름 속 줄바꿈이 한 항목을 둘로 쪼개지 못합니다.
    `--parser hcl2` 는 JSON 구성을 읽지 못하므로 `.tf.json` 은 이 트리에 두지
    않습니다.
*   R13 은 guard 가 정책에 넘기는 `.tf` 만 봅니다. 추적되는 `*.example`
    (`terraform.tfvars.example` · `backend.hcl.example`) · 시험 파일
    (`*.tftest.hcl`) · 주석(hcl2 가 버림)은 보지 않으므로 리뷰가 봅니다. 계정
    ID 꼴은 12자리 숫자열과 4-4-4 대시 표기뿐이고, 그 밖의 꼴(공백 · 점 구분
    등)도 리뷰 몫입니다.
*   R1 은 Secrets Manager 비밀 버전만 봅니다. `aws_ssm_parameter` 의
    SecureString 값도 상태에 평문으로 남지만 R1 대상이 아닙니다(설계상 SSM
    비밀은 없음 — 쓰게 되면 리뷰가 막고 규칙을 더합니다).
*   파일 첫머리 UTF-8 BOM 과 줄끝 CR(CRLF)은 conftest 의 hcl2 파서가 그대로
    읽습니다(Terraform 도 받음). BOM · CRLF 파일 안의 금지 선언도 잡힙니다
    (`bad_R1_bom` · `bad_R1_crlf`).
*   conftest 는 `count` 를 펼치지 않으므로 `count = 0` 인 금지 자원도
    위반입니다. 반대로 식을 평가하지 않으므로 「true 와 같은 뜻의 식」도 R4 ·
    R11 위반입니다(엄격한 쪽).
