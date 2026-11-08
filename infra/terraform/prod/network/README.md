# prod/network

prod 전용 VPC 를 만드는 루트입니다(설계 3-1 의 ④). 런타임 1단(RDS) ·
platform · media · edge 가 이 루트의 출력을 씁니다.

*   AZ 두 개에 퍼블릭 · 프라이빗 서브넷을 하나씩 둡니다. VPC CIDR 을 네
    등분해 앞 둘은 퍼블릭, 뒤 둘은 프라이빗(`azs` 순서)에 줍니다.
*   NAT 게이트웨이는 `nat_az` 에 하나만 둡니다. 프라이빗 라우트 테이블은 AZ
    마다 두되 모두 그 NAT 로 나갑니다.
*   S3 Gateway 엔드포인트를 프라이빗 라우트 테이블에만 붙입니다.
*   AWS API 인터페이스 엔드포인트 다섯(`ecr.api` · `ecr.dkr` · `logs` ·
    `secretsmanager` · `sqs`)을 `nat_az` 의 프라이빗 서브넷에 둡니다
    (POK-408 — 아래 「인터페이스 엔드포인트」). private DNS 가 켜져 있어 VPC
    의 모든 호출자가 이 다섯 이름을 엔드포인트로 풉니다.
*   피어링 · TGW 는 없습니다 — dev VPC 와 SG 참조가 생길 수 없습니다(설계
    2-3 「네트워크」).
*   VPC 기본 SG 는 다루지 않습니다. 기본 SG 는 같은 SG 끼리 전부 허용하므로,
    이 VPC 를 쓰는 후속 루트의 모든 자원은 전용 SG 를 지정하고 기본 SG 를
    쓰지 않습니다([런타임 트랙 계약](/infra/terraform/CONTRACT-runtime.md)
    「SG 규칙 규약」).

자원 Name 태그는 `pokeclip-prod-<용도>` 입니다(계획 3절 「한 계정 규약」).
provider 의 `default_tags` 네 키(`TerraformRoot = prod/network`)는 태그를
받는 자원(`tags_all` 이 있는 자원)에 붙습니다. mock 시험은 태그를 보지
못하므로 plan 의 `tags_all` 로 확인합니다.

## 값과 적용

`cidr` · `azs` · `media_az` · `nat_az` 는 기본값이 없습니다.
`terraform.tfvars.example` 을 `terraform.tfvars`(추적 안 함)로 복사해 채웁니다.
`cidr` 는 `/16` 부터 `/25` 까지의 네트워크 주소입니다 — 네 등분한 서브넷이
ALB 서브넷 최소인 `/27` 이상이 되게 합니다. dev 상자 VPC 와 겹치지 않게
고르고, O7a 선행 0(단계 직전 점검)에서 dev VPC CIDR 을 읽어 확인합니다.
적용은 O7a 의 1(kty 승인)입니다.

`interface_endpoint_private_dns`(기본 `true`)는 되돌리기 스위치입니다. tfvars
에 두지 않습니다 — 「되돌리기 1단계」 에서만 `false` 한 줄을 넣고, 「다시
켜기」 에서 그 줄을 지웁니다.

백엔드는 `backend.hcl.example` 을 `backend.hcl` 로 복사하고 `bucket`
자리표시자 `<STATE_BUCKET_PROD>` 를 `pokeclip-tfstate-prod-apne2` 로 고친 뒤
`init -backend-config=backend.hcl` 로 엽니다(운영 단계).

## 출력

소비 루트와의 계약입니다(설계 3-2). 이름을 바꾸지 않습니다.

| 출력 | 쓰는 곳 |
|---|---|
| `vpc_id` · `private_subnet_ids` | 런타임 1단 — RDS 서브넷 그룹 · RDS SG |
| `vpc_id` · `public_subnet_ids` · `private_subnet_ids` | platform — 공개 ALB · 내부 ALB · ECS |
| `media_az` · `media_public_subnet_id` | platform — NLB |
| `media_private_subnet_id` · `s3_prefix_list_id` | media — Media 상자 · SG 이그레스 |
| `vpc_id` | edge — 서울 별칭 data source |
| `vpc_cidr` | 참고용 |
| `interface_endpoint_sg_id` | workers(PR 488 뒤) — render 이그레스 대상 |
| `interface_endpoint_services` · `interface_endpoint_private_dns` | workers(PR 488 뒤) — 계약 검사(다섯 · private DNS 켬) |

## 인터페이스 엔드포인트

render 일꾼의 인터넷 이그레스(`0.0.0.0/0:443`)를 닫으려고 둡니다(POK-408).
render 는 이 다섯과 S3 게이트웨이 · clip 에만 나가게 됩니다(PR 488).

| 무엇 | 값 |
|---|---|
| 서비스 | `ecr.api` · `ecr.dkr` · `logs` · `secretsmanager` · `sqs` — Fargate 기동(이미지 · 로그 · 비밀)과 일꾼 수신에 쓰는 것 |
| 넣지 않는 것 | `ssm` · `ssmmessages` · `ec2messages` · `sts` · `kms` — Media 상자의 SSM 은 지금처럼 NAT 로 갑니다 |
| 자리 | `nat_az` 의 프라이빗 서브넷 하나 |
| SG | `pokeclip-prod-vpce` — 인그레스 TCP 443 · 출처 VPC CIDR 하나, 이그레스 규칙 없음 |
| 정책 | 붙이지 않음(기본 전체 허용 — 「알려진 한계」 (a)) |
| private DNS | 켬(`interface_endpoint_private_dns`) |

엔드포인트는 인그레스 규칙이 생긴 뒤에 만들어집니다(`depends_on`). 엔드포인트가
생기는 순간이 곧 private DNS 전환이라, 전환 순간부터 VPC 의 호출자가 닿습니다.
지울 때는 엔드포인트가 먼저 사라지고 규칙이 뒤에 회수됩니다.

### 비용과 장애 영역

*   **비용:** 서울은 엔드포인트 하나 · AZ 하나에 시간당 $0.013, 처리량 GB 당
    $0.01 입니다. 다섯이면 월 약 $47.5(0.013 × 5 × 730 시간)에 처리량이
    더해집니다. NAT 는 다른 서비스(SSM · 외부 API)가 계속 쓰므로 NAT 비용을
    줄이지 않고 그 위에 더해집니다.
*   **장애 영역:** 엔드포인트가 `nat_az` 하나에만 있어, 그 AZ 가 손상되면
    다른 AZ 의 호출자도 이 다섯 서비스를 잃습니다. NAT 도 같은 AZ 하나라
    장애 영역은 지금과 같습니다.

### 서브넷 주소 여유

엔드포인트 ENI 다섯이 `nat_az` 프라이빗 서브넷의 주소 다섯을 씁니다. prod
`cidr` 는 `/16` 이라(2026-11-07 tfvars 확인) 서브넷 하나가 `/18` 이고, AWS
예약 다섯을 빼면 16,379 개입니다. 그 서브넷에 지금 사는 ENI(platform 태스크
· 내부 ALB · RDS · Media 상자 · 일꾼)는 수십 개라 다섯은 여유 안입니다.
apply 전에 실물로 한 번 읽습니다(아래 「준비」 의 변수를 둔 뒤 — 5 를 넘으면
됨).

```shell
aws ec2 describe-subnets --region "${region}" \
  --filters "Name=vpc-id,Values=${vpc_id}" \
  "Name=tag:Name,Values=pokeclip-prod-private-${nat_az}" \
  --query 'Subnets[0].AvailableIpAddressCount' --output text
```

### 알려진 한계

render 가 인터넷의 임의 HTTPS 호스트에 직접 연결하는 길은 닫힙니다(SSRF ·
원격 조종 서버 연결). 다음 둘은 남습니다.

*   **(a) 다른 계정의 자원으로 쓰기.** 공격자가 자기 자격 증명으로 서명한
    요청을 허용된 AWS 서비스(엔드포인트 다섯 · S3 게이트웨이)로 보내면 다른
    계정의 자원에 쓸 수 있습니다. 엔드포인트와 게이트웨이의 기본 정책은
    `Principal * · Action * · Resource *` 이고, S3 접두사 목록은 리전의 서비스
    IP 범위일 뿐 계정 허용 목록이 아닙니다. 엔드포인트 정책(데이터 경계)이
    막는 몫이고 후속 후보입니다.
*   **(b) DNS 질의 이름으로 내보내기.** VPC 리졸버에 보내는 질의 이름에
    데이터를 실어 보낼 수 있습니다. SG 는 Route 53 Resolver 질의를 거르지
    못합니다. Route 53 DNS Firewall 이 막는 몫이고 후속 후보입니다.

## 엔드포인트 적용과 확인

PR-N(이 루트의 엔드포인트) 머지 전후의 절차입니다. 확인은 「오류 없음」 이
아니라 **새 연결을 강제한 실제 호출자의 성공**으로 판정합니다 — 기존 연결 ·
연결 풀(SDK · awslogs 의 keep-alive)은 DNS 캐시보다 오래 옛 NAT 경로를 타
결함을 가릴 수 있습니다. 이 시점엔 일꾼이 없습니다(PR 488 은 그 뒤).

*   실행자는 kty 입니다. **kty 로컬 터미널**에서 돌립니다. plan · 상태 · 조회
    출력에 계정 ID 가 든 ARN 이 섞이므로, 원문은 증거 디렉터리의 파일로만
    두고 화면 · 세션 · 채팅에는 판정 줄만 옮깁니다. 명령의 오류 출력
    (`AccessDenied` 등)에도 ARN 이 나옵니다 — 오류 줄도 그대로 붙여 넣지
    않고, 계정 ID 를 가린 뒤 옮깁니다.
*   판정 함수는 마지막 줄에 판정을 내고 종료 코드로도 돌려줍니다 — 0 통과 ·
    1 멈춤(실패) · 2 판정불가. **판정불가는 통과가 아닙니다.**
*   아래 셸 블록은 `scripts/test/network_vpce_runbook_test.sh` 가 그대로 뽑아
    가짜 aws 로 시험합니다. 블록을 고치면 그 시험을 다시 돌립니다.
*   함수 정의 블록(`vpce_…() {`)은 절마다 그 함수를 부르는 블록보다 앞에
    있습니다 — 문서 순서대로 붙여 넣으면 됩니다(시험이 단언). 셸을 새로
    열었으면 「준비」 의 블록부터 다시 둡니다.

### 준비

`prod/network` 를 `init -backend-config=backend.hcl` 로 연 체크아웃의 저장소
뿌리에서 돌립니다. 작업 디렉터리(plan 파일)는 저장소 밖에, 결과 원문은 증거
루트 아래 이 작업 전용 디렉터리(0700)에 둡니다([단계 직전 점검
런북](/infra/terraform/runbooks/stage-precheck.md) 「증거」).

```shell
umask 077
region=ap-northeast-2
cluster=pokeclip-prod
network=infra/terraform/prod/network
work="$(mktemp -d "${TMPDIR:-/tmp}/pokeclip-vpce.XXXXXX")"
evidence="${HOME}/.pokeclip-precheck/network-vpce-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "${evidence}" && chmod 700 "${evidence}"
export AWS_REGION="${region}" AWS_DEFAULT_REGION="${region}"
```

```shell
vpc_id="$(terraform -chdir="${network}" output -raw vpc_id)"
nat_az="$(echo 'var.nat_az' | terraform -chdir="${network}" console | tr -d '"')"
```

판정 함수가 함께 쓰는 것입니다. `vpce_task_starts` 는 서비스의 실행 중
태스크마다 기동 시각(epoch 초)을 한 줄씩 냅니다 — 서비스가 없으면(MISSING ·
INACTIVE) 종료 3, 조회가 실패하면 종료 2 입니다. `vpce_describe_tasks` 는
`list-tasks` 출력의 태스크를 읽어, 목록 · 결과를 읽지 못하거나 결과가 요청과
다르면(`failures` 가 있거나 돌려준 수가 다름) 종료 2 입니다 — 빠진 태스크를
조용히 건너뛰면 관문이 그 태스크를 보지 않고 통과합니다. `describe-tasks` 는
한 번에 100개까지라, 그보다 많으면 거부돼 판정불가입니다(kty 회부).

```shell
# ISO 8601(소수 초 · Z 또는 ±hh:mm) 또는 epoch 초를 epoch 초로 바꾸는 jq 정의.
vpce_jq_epoch='def epoch:
  if type == "number" then floor
  else (capture("^(?<d>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})([.][0-9]+)?(?<z>Z|[+-][0-9]{2}:?[0-9]{2})$")
      // error("시각 꼴이 아님"))
    | (.d + "Z" | fromdateiso8601) as $t
    | if .z == "Z" then $t
      else ((.z[1:3] | tonumber) * 3600 + (.z[-2:] | tonumber) * 60) as $off
        | if .z[0:1] == "+" then $t - $off else $t + $off end
      end
  end;'

vpce_iso() {  # vpce_iso <epoch 초> — UTC ISO 8601
  jq -rn --argjson t "$1" '$t | todate'
}

vpce_endpoints() {  # 인터페이스 엔드포인트 → ${evidence}/10-endpoints.json
  aws ec2 describe-vpc-endpoints --region "${region}" \
    --filters "Name=vpc-id,Values=${vpc_id}" Name=vpc-endpoint-type,Values=Interface \
    --output json > "${evidence}/10-endpoints.json"
}

vpce_describe_tasks() {  # vpce_describe_tasks <list-tasks 출력> <결과 파일>
  local n
  if ! n="$(jq -er '.taskArns | length' "$1")"; then
    return 2
  fi
  if [ "${n}" -eq 0 ]; then
    echo '{"tasks": [], "failures": []}' > "$2"
    return 0
  fi
  aws ecs describe-tasks --region "${region}" --output json --cli-input-json \
    "$(jq -c --arg c "${cluster}" '{cluster: $c, tasks: .taskArns}' "$1")" \
    > "$2" || return 2
  jq -e --argjson n "${n}" \
    '((.failures // []) | length) == 0 and (.tasks | length) == $n' "$2" > /dev/null \
    || return 2
}

vpce_task_starts() {  # vpce_task_starts <서비스 짧은 이름(clip 등)>
  local name="${cluster}-$1" dir="${work}/tasks-$1"
  mkdir -p "${dir}" || return 2
  aws ecs describe-services --region "${region}" --cluster "${cluster}" \
    --services "${name}" --output json > "${dir}/services.json" || return 2
  if jq -e '(.failures | any(.reason == "MISSING"))
      or (.services | any(.status == "INACTIVE"))' "${dir}/services.json" > /dev/null; then
    return 3
  fi
  jq -e '.services | length == 1' "${dir}/services.json" > /dev/null || return 2
  aws ecs list-tasks --region "${region}" --cluster "${cluster}" \
    --service-name "${name}" --desired-status RUNNING --output json \
    > "${dir}/tasks.json" || return 2
  vpce_describe_tasks "${dir}/tasks.json" "${dir}/described.json" || return 2
  jq -r "${vpce_jq_epoch}"' .tasks[] | select(.startedAt != null) | .startedAt | epoch' \
    "${dir}/described.json" || return 2
}
```

### 적용 1 — 머지 전 plan

PR-N 머리에서 `prod/network` plan 을 만들고 요지를 kty 가 봅니다. 요지는 [prod
철거 런북](/infra/terraform/runbooks/prod-teardown.md) 「plan 요지」 의
`plan_summary` 로 뽑습니다(그 블록을 먼저 둡니다).

```shell
terraform -chdir="${network}" plan -input=false -out="${work}/network.tfplan" \
  > "${evidence}/01-plan.txt"
plan_summary "${network}" "${work}/network.tfplan" | sort \
  | tee "${evidence}/01-plan-summary.txt"
```

기대 요지는 일곱 줄이고 모두 `create` 입니다. 그 밖의 줄이 하나라도 있으면
멈추고 kty 에게 넘깁니다.

```
create module.network.aws_security_group.interface_endpoints[0]
create module.network.aws_vpc_endpoint.interface["ecr.api"]
create module.network.aws_vpc_endpoint.interface["ecr.dkr"]
create module.network.aws_vpc_endpoint.interface["logs"]
create module.network.aws_vpc_endpoint.interface["secretsmanager"]
create module.network.aws_vpc_endpoint.interface["sqs"]
create module.network.aws_vpc_security_group_ingress_rule.interface_endpoints_https[0]
```

### 적용 2 — 다른 루트 plan

develop 과 PR-N 머리 양쪽에서 platform · media · runtime · edge plan 이
종료 0 인지 봅니다(`-detailed-exitcode` 없이 — 변경 수가 아니라 「출력 추가가
소비 루트를 흔들지 않음」 을 봅니다). 이 루트들의 코드는 바뀌지 않고 network
출력은 더하기만 하므로 처음부터 종료 0 이 예상됩니다.

### 적용 3 — 머지 · O7a · apply

머지 → 단계 직전 점검 `O7a` 재판정(새 행이 CHANGED 로 나오는 것은 정상 —
kty 가 수용) → `terraform -chdir="${network}" apply "${work}/network.tfplan"`
(kty 승인 · 머지 체크아웃에서 plan 을 다시 만든 뒤). apply 가 끝난 시각을
`date -u +%Y-%m-%dT%H:%M:%SZ > "${evidence}/02-applied-at.txt"` 로 남깁니다.

### 확인 (ㄱ) 상태

```shell
vpce_endpoint_states() {  # 엔드포인트마다 「<서비스> <상태> dns=… eni=…」
  jq -r '.VpcEndpoints[] | "\(.ServiceName | split(".")[3:] | join(".")) \(.State) dns=\(.PrivateDnsEnabled) eni=\(.NetworkInterfaceIds | length)"' \
    "${evidence}/10-endpoints.json" | sort
}
```

```shell
vpce_endpoints
vpce_endpoint_states
```

기대는 다섯 줄 `ecr.api available dns=true eni=1` 꼴(서비스 다섯)입니다.

### 확인 (ㄴ) 이름 해석

Media 상자에서 다섯 이름을 풀어, 모두 엔드포인트 ENI 주소로 풀리는지 봅니다.
엔드포인트 SG 의 인그레스 출처가 VPC CIDR 하나이고 모든 호출자의 이그레스가
`0.0.0.0/0:443` 이므로, 이 해석이 VPC 의 모든 호출자에 같다는 것이 R1(VPC
전역 전환)의 주된 근거입니다. SSM 명령이라 **실행 직전 kty 승인**입니다.
이름의 캐시(TTL 60초)가 지나게 apply 뒤 1분 넘어 돌립니다.

[Media 교체 런북](/infra/terraform/runbooks/media-swap.md) 「준비」 의 두 블록
(작업 디렉터리와 상태 · SSM 명령 보내기)을 먼저 둡니다. 그 첫 블록이 `work`
를 새로 만들므로, 이 절의 작업 디렉터리를 먼저 보관합니다.

```shell
vpce_work="${work}"
```

두 블록을 둔 뒤, 그 블록이 만든 작업 디렉터리를 지우고 이 절의 것으로
되돌립니다. `media_ssm` 은 되돌린 `work` 에 씁니다.

```shell
if [ "${work}" != "${vpce_work:?}" ]; then rm -r -- "${work:?}"; fi
work="${vpce_work:?}" && unset vpce_work
```

판정 함수입니다. 켬(`on`)은 다섯 이름이 모두 풀리고 모든 주소가 ENI 주소일
때, 끔(`off`)은 다섯 이름이 모두 풀리고 어느 주소도 ENI 주소가 아닐 때
통과입니다. 출력의 계정 ID 는 `<계정>` 으로 가립니다.

`vpce_resolve` 는 상자에 탐침을 보내고 판정합니다. 상자 키는 `media_ids` 의
상자 하나에서 고릅니다(`vpce_media_box`) — 교체 중이라 상자가 둘이면 어느
상자인지 정하지 못해 판정불가(2)입니다. 계정 조회나 SSM 명령이 실패해도
판정불가입니다. 이름을 보지 못한 것이지 이름이 틀리게 풀린 것이 아니므로
「실패」(1)로 세지 않습니다. ENI 주소 기록(`12-eni-ips.txt`)이 다섯 줄이
아니어도(노트북 쪽 조회가 실패해 비었거나 없음) 상자에 보내기 전에
판정불가입니다 — 대조할 주소가 없으면 판정 함수가 상자 출력을 ENI 주소로
잘못 읽어 「실패」 를 냅니다.

```shell
vpce_resolve_verdict() {  # vpce_resolve_verdict on|off <상자 출력> <ENI 주소>
  awk -v mode="$1" '
    NR == FNR { eni[$1] = 1; next }
    !match($2, /^[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+$/) { next }
    {
      names[$1] = 1
      if (($2 in eni) != (mode == "on")) { bad[$1] = 1 }
    }
    END {
      for (n in names) { count++ }
      for (n in bad) {
        sub(/^[0-9]+[.]dkr[.]/, "<계정>.dkr.", n)
        print "어긋남 " n
        failed = 1
      }
      if (count != 5) {
        print "이름 " count + 0 "개만 풀림(다섯이어야 함)"
        failed = 1
      }
      print "이름 해석(" mode "): " (failed ? "실패" : "통과")
      exit failed
    }' "$3" "$2"
}

vpce_media_box() {  # 떠 있는 상자 키 — media_ids 의 상자가 하나가 아니면 실패
  media_ids | jq -er 'keys | if length == 1 then .[0] else empty end'
}

vpce_resolve() {  # vpce_resolve on|off <결과 파일> — 통과 0 · 실패 1 · 판정불가 2
  local box account ssm_rc
  if [ "$(grep -c . "${evidence}/12-eni-ips.txt" 2> /dev/null)" != 5 ]; then
    echo "이름 해석($1): 판정불가 — ENI 주소 기록이 다섯이 아님(12-eni-ips.txt)"
    return 2
  fi
  if ! box="$(vpce_media_box)"; then
    echo "이름 해석($1): 판정불가 — media_ids 의 상자가 하나가 아님"
    return 2
  fi
  if ! account="$(aws sts get-caller-identity --query Account --output text)"; then
    echo "이름 해석($1): 판정불가 — 계정 조회 실패"
    return 2
  fi
  media_ssm "${box}" "${work}/vpce-resolve.sh" "${region}" "${account}" > "$2"
  ssm_rc=$?
  if [ "${ssm_rc}" -ne 0 ]; then
    echo "이름 해석($1): 판정불가 — SSM 명령 실패(상자 ${box} · 종료 ${ssm_rc})"
    return 2
  fi
  vpce_resolve_verdict "$1" "$2" "${evidence}/12-eni-ips.txt"
}
```

```shell
aws ec2 describe-network-interfaces --region "${region}" \
  --network-interface-ids $(jq -r '.VpcEndpoints[].NetworkInterfaceIds[]' "${evidence}/10-endpoints.json") \
  --query 'NetworkInterfaces[].PrivateIpAddress' --output text \
  | tr '\t' '\n' > "${evidence}/12-eni-ips.txt"
wc -l < "${evidence}/12-eni-ips.txt"   # 5 — 아니면 vpce_resolve 가 판정불가
cat > "${work}/vpce-resolve.sh" <<'SH'
for name in "api.ecr.$1.amazonaws.com" "$2.dkr.ecr.$1.amazonaws.com" \
  "logs.$1.amazonaws.com" "secretsmanager.$1.amazonaws.com" \
  "sqs.$1.amazonaws.com"; do
  getent ahostsv4 "${name}" | awk -v n="${name}" '{ print n, $1 }' | sort -u
done
SH
vpce_resolve on "${evidence}/20-resolve-on.txt"
```

### 확인 (ㄷ) clip 강제 재기동

clip 서비스 하나를 같은 태스크 정의로 다시 띄워(`--force-new-deployment` —
Terraform apply 아님) 다섯 경로를 새 연결로 씁니다: ECR 인증 · 풀(ecr.api ·
ecr.dkr · S3 레이어) · 비밀 주입(secretsmanager) · 로그 전송(logs) · 수명 주기
큐 긴 폴링(sqs). 운영 서비스 재기동이라 **kty 승인**입니다(network apply 승인
묶음).

운영 영향(승인 요청에 적습니다): 롤링이라 새 태스크가 healthy 가 된 뒤 옛
태스크가 내려가고(최소 정상 비율 = ECS 기본 100%), 기동이 실패하면 옛 태스크가
남습니다. 옛 태스크가 내려갈 때 SSE 연결이 한 번 끊겨 브라우저가 다시 붙습니다.
겹치는 동안 두 태스크가 수명 주기 FIFO 를 함께 소비하지만 멱등이라 안전하고,
썸네일 정리기가 같은 대상을 중복 발행해 card · clip 사진의 재시도 3회 중 하나를
더 쓸 수 있습니다. 겹침의 영향은 평소 clip 배포와 같습니다.

#### 재기동 전 비밀 관문

재기동하면 버전을 지정하지 않은 비밀은 `AWSCURRENT` 로 다시 주입됩니다. 회전
직후라 실행 중인 다른 서비스가 옛 값을 들고 있으면, 새 clip 만 새 값을 받아
내부 호출이 401 이 됩니다. 그래서 clip 이 받는 비밀마다 그 비밀을 받는 **모든**
서비스의 실행 중 태스크 `startedAt` 이 그 비밀의 `LastChangedDate` 보다 뒤여야
진행합니다.

| 비밀 그릇 | clip 의 env | 소비 서비스 |
|---|---|---|
| `pokeclip/prod/jwt-secret` | `JWT_SECRET` | auth · clip |
| `pokeclip/prod/internal-api-token` | `INTERNAL_API_TOKEN` | auth · clip · chat-collector · chat-detector · render · upload(일꾼은 workers apply 뒤) |
| `pokeclip/prod/signing-key` | `CLOUDFRONT_PRIVATE_KEY_PEM` | clip |

근거는 `prod/runtime/app_secrets.tf` 의 `app_secret_env_names` 와
`prod/platform/services.tf` 의 clip 서명 키입니다. 관문은 먼저 clip 의 지금
태스크 정의에서 비밀 셋을 읽어 이 표의 clip 줄과 같은지 봅니다 — 다르면
판정불가입니다. 대체 갈래(`POSTGRES_PASSWORD`)를 쓰는 날에는 그 그릇 줄을
더합니다. 서비스가 아직 없으면(render · upload — PR 488 전) 그 줄을
「건너뜀」 으로 남기고 셉니다.

```shell
# 비밀 그릇 → 소비 서비스(위 표). 한 줄에 한 쌍.
vpce_secret_consumers() {
  printf '%s\n' \
    'pokeclip/prod/jwt-secret auth' \
    'pokeclip/prod/jwt-secret clip' \
    'pokeclip/prod/internal-api-token auth' \
    'pokeclip/prod/internal-api-token clip' \
    'pokeclip/prod/internal-api-token chat-collector' \
    'pokeclip/prod/internal-api-token chat-detector' \
    'pokeclip/prod/internal-api-token render' \
    'pokeclip/prod/internal-api-token upload' \
    'pokeclip/prod/signing-key clip'
}

vpce_current_version() {  # vpce_current_version <describe-secret 출력>
  jq -er '[.VersionIdsToStages // {} | to_entries[]
      | select(.value | index("AWSCURRENT")) | .key]
    | if length == 1 then .[0] else empty end' "$1"
}

# 결과: 진행 0 · 멈춤 1 · 판정불가 2. 버전 기록 = ${evidence}/30-secret-versions.txt
vpce_secret_gate() {
  local record="${evidence}/30-secret-versions.txt" stop=0
  local td expected actual secret svc changed version starts start rc
  : > "${record}"
  if ! aws ecs describe-services --region "${region}" --cluster "${cluster}" \
      --services "${cluster}-clip" --output json > "${work}/clip-service.json" \
    || ! td="$(jq -er '.services[0].taskDefinition' "${work}/clip-service.json")" \
    || ! aws ecs describe-task-definition --region "${region}" --task-definition "${td}" \
      --output json > "${work}/clip-taskdef.json"; then
    echo '비밀 관문: 판정불가 — clip 태스크 정의 조회 실패'
    return 2
  fi
  actual="$(jq -r '[.taskDefinition.containerDefinitions[].secrets[]?.valueFrom
      | sub("^arn:[^:]+:secretsmanager:[^:]+:[0-9]+:secret:"; "") | sub("-[A-Za-z0-9]{6}$"; "")]
    | unique | join(" ")' "${work}/clip-taskdef.json")"
  expected="$(vpce_secret_consumers | jq -Rrs '[split("\n")[] | select(length > 0)
    | split(" ") | select(.[1] == "clip") | .[0]] | unique | join(" ")')"
  if [ -z "${actual}" ] || [ "${actual}" != "${expected}" ]; then
    echo "clip 비밀 셋: ${actual:-없음} (표: ${expected})"
    echo '비밀 관문: 판정불가 — clip 태스크 정의의 비밀 셋이 표와 다름'
    return 2
  fi
  for secret in $(vpce_secret_consumers | cut -d' ' -f1 | LC_ALL=C sort -u); do
    if ! aws secretsmanager describe-secret --region "${region}" --secret-id "${secret}" \
        --output json > "${work}/secret.json" \
      || ! changed="$(jq -er "${vpce_jq_epoch}"' .LastChangedDate | epoch' "${work}/secret.json")" \
      || ! version="$(vpce_current_version "${work}/secret.json")"; then
      echo "비밀 관문: 판정불가 — ${secret} 조회 실패"
      return 2
    fi
    printf '%s %s %s\n' "${secret}" "${version}" "${changed}" >> "${record}"
    for svc in $(vpce_secret_consumers | awk -v s="${secret}" '$1 == s { print $2 }'); do
      starts="$(vpce_task_starts "${svc}")"
      rc=$?
      if [ "${rc}" -eq 3 ]; then
        echo "건너뜀 ${secret} ${svc}: 서비스 없음"
        continue
      fi
      if [ "${rc}" -ne 0 ]; then
        echo "비밀 관문: 판정불가 — ${svc} 태스크 조회 실패"
        return 2
      fi
      while IFS= read -r start; do
        [ -n "${start}" ] || continue
        if [ "${changed}" -ge "${start}" ]; then
          echo "멈춤 ${secret} ${svc}: 회전 $(vpce_iso "${changed}") ≥ 기동 $(vpce_iso "${start}")"
          stop=1
        fi
      done <<< "${starts}"
    done
  done
  if [ "${stop}" -ne 0 ]; then
    echo '비밀 관문: 멈춤 — 재기동하지 않고 kty 에게 넘김'
    return 1
  fi
  echo '비밀 관문: 진행'
}

# 확인이 끝난 뒤 버전이 그대로인지(바뀌었으면 판정 무효 · kty 회부).
vpce_secret_recheck() {
  local record="${evidence}/30-secret-versions.txt" invalid=0 secret version now
  if [ ! -s "${record}" ]; then
    echo '비밀 버전: 판정불가 — 관문 기록 없음'
    return 2
  fi
  while read -r secret version _; do
    if ! aws secretsmanager describe-secret --region "${region}" --secret-id "${secret}" \
        --output json > "${work}/secret-recheck.json" < /dev/null \
      || ! now="$(vpce_current_version "${work}/secret-recheck.json")"; then
      echo "비밀 버전: 판정불가 — ${secret} 조회 실패"
      return 2
    fi
    if [ "${now}" != "${version}" ]; then
      echo "바뀜 ${secret}: 확인 중 AWSCURRENT 가 옮겨 감"
      invalid=1
    fi
  done < "${record}"
  if [ "${invalid}" -ne 0 ]; then
    echo '비밀 버전: 무효 — 확인 판정을 버리고 kty 에게 넘김'
    return 1
  fi
  echo '비밀 버전: 그대로'
}
```

`vpce_secret_gate` 가 `진행` 일 때만 재기동합니다. `멈춤` 은 네트워크 실패가
아니므로 되돌리기가 아니라 kty 회부입니다. 확인 (ㄷ) 의 판정이 끝나면
`vpce_secret_recheck` 를 돌립니다.

#### 재기동과 판정

판정 대상은 이 배포의 새 태스크 **전부**입니다(`startedBy` = 배포 id). 판정은
넷이고 모두 만족해야 합니다.

1.  배포 하나가 `COMPLETED` · 실행 수 = 원하는 수 · 이벤트와 멈춘 새 태스크에
    `CannotPullContainerError` · `ResourceInitializationError` 없음(ECR ·
    Secrets 성공).
1.  새 태스크마다 로그 스트림(`clip/clip/<태스크 id>`)이 재기동 명령 시각 뒤에
    생겼고 첫 이벤트가 그 태스크의 기동 시각(`startedAt`) 뒤 3분 안에 있음
    (logs 성공 — 로그 경로가 깨지면 스트림이 생기지 않습니다). 기준이 재기동
    시각이 아닌 것은 새 태스크를 재기동 뒤 4분까지 기다리기 때문입니다 —
    3분 넘어 뜬 정상 태스크가 실패로 읽히지 않게 합니다.
1.  새 태스크마다 아래 SQS 판정이 통과.
1.  판정이 끝난 뒤 `vpce_secret_recheck` 가 `그대로`.

2 · 3 은 새 태스크가 0개면 판정불가(2)입니다 — 「새 태스크마다」 가 빈
목록에서 저절로 참이 되지 않게 합니다. 기다림에는 모두 상한이 있습니다. 새 태스크
기동은 재기동 명령 시각(`40-restart-at.txt`)부터 4분, 배포 끝은 같은 시각부터
10분, SQS 표본은 새 태스크 기동 뒤 15분까지입니다. 새 태스크가 끝내 뜨지 않아도
재기동 뒤 10분 안에 판정불가로 끝납니다. 재기동 시각 기록이 없거나 비었으면
(재기동 블록을 돌리지 않은 셸 등) 두 대기는 기다리지 않고 `재기동 시각 기록
없음` 으로 판정불가입니다.

**SQS 판정의 신호.** clip 헬스는 수신에 한 번도 성공하지 않아도 루프 시작 뒤
2분 동안 `UP(status=starting)` 이고(`IntakeHealth.java`), ALB 는 첫 헬스 성공으로
대상을 healthy 로 올립니다. 그래서 「배포 COMPLETED · 대상 healthy」 는 SQS
증거가 아닙니다. 수신 성공 시각 `lastPollSucceededAt` 은 clip
`/actuator/health` 의 상세에만 있는데, 지금 그 응답을 읽을 길이 없습니다.

*   Media 상자에서는 닿지 않습니다 — 상자 SG 의 이그레스는 S3 · `0.0.0.0/0:443`
    · RDS · auth 넷뿐이고(`modules/media_host/security_groups.tf`), clip SG 의
    인그레스 출처에 상자 SG 가 없습니다(`prod/platform/services.tf`).
*   공개 ALB 는 `/api/clip/*` 만 clip 으로 보내고 나머지는 고정 404 입니다.
*   ECS Exec 는 꺼져 있고, 켜려면 platform apply 가 필요합니다.
*   clip 은 수신 성공을 로그로 남기지 않습니다(`poll_failed` 만 남김).

그래서 판정은 **대리 신호**로 합니다 — 새 태스크의 대상 그룹 상태 표본(30초
마다)과 그 태스크 로그의 `broadcast.intake.poll_failed` 시각입니다. 헬스가
DOWN 이면(마지막 성공에서 2분이 지남) ALB 가 90초 안에 대상을 unhealthy 로
내리므로, 대상 상태가 `lastPollSucceededAt` 의 대리입니다(kty 2026-11-07 선택).

*   **한계:** 헬스의 다른 항목(DB 등)이 DOWN 이어도 같은 실패로 보이고,
    `lastPollSucceededAt` 값 자체는 보지 못합니다. 판정이 실패 쪽으로만
    기울므로(SQS 가 멀쩡해도 실패로 볼 수는 있지만, 깨진 SQS 를 통과로 보지는
    않음) 안전합니다. 실패가 나오면 되돌리기 전에 그 태스크 로그에서 원인
    항목을 봅니다.
*   **ALB 값:** 대상 그룹 헬스 검사는 간격 30초 · healthy 임계 3 · unhealthy
    임계 3 입니다. `modules/alb` 의 `health_check` 블록이 간격 · 임계값을
    넘기지 않아 provider 기본값이 삽니다 — terraform-provider-aws v6.68.0
    `internal/service/elbv2/target_group.go` 의 `health_check` 스키마
    (`interval` 30 · `healthy_threshold` 3 · `unhealthy_threshold` 3).
*   **대상 그룹:** clip 서비스는 대상 그룹 둘(`clip` · `clip-sse`)에 붙고 둘의
    헬스 검사는 같습니다. ARN 은 `ecs describe-services` 의
    `loadBalancers[].targetGroupArn` 에서 받고 첫 번째 것을 봅니다.

*   **연속 실패 = 실패:** healthy 가 된 뒤의 표본 하나라도 healthy 가 아님(2분
    넘게 성공 없이 실패만 이어짐). 기준을 「2분 유예가 끝난 뒤」 대신 「처음
    healthy 가 된 뒤」 로 잡는 것은, 수신 루프 시작 시각이 로그에 없고 JVM
    기동 중의 unhealthy(앱이 아직 듣지 않음)는 SQS 와 무관하기 때문입니다.
    기동 유예 2분은 첫 healthy 를 만든 `starting` 몫이라 그 뒤의 unhealthy 는
    유예가 끝난 뒤의 것입니다.
*   **실패 뒤 갱신 없음 = 실패:** 마지막 `poll_failed` 뒤 240초(120 + 90 +
    표본 간격 30)가 지난 표본이 healthy 여야 합니다. healthy 면 그 실패 뒤에 수신 성공이 있었다는
    뜻입니다. 그 시각까지 표본이 모자라면 `대기` 이고 표본을 더 받습니다.
*   창 끝 표본이 healthy 가 아니면 실패입니다.

**창 길이.** 기준은 새 태스크의 `startedAt` 입니다(수신 루프 시작은 로그에
없습니다).

| 몫 | 초 | 근거 |
|---|---|---|
| JVM 기동 상한 | 180 | clip 의 ECS 헬스 유예(`prod/platform/variables.tf` `services.clip.health_check_grace`) — 이보다 늦으면 ECS 가 태스크를 갈아 배포가 COMPLETED 가 아닙니다 |
| 기동 유예(starting) | 120 | `IntakeHealth.STALE_AFTER` |
| ALB 판정 지연 | 90 | 대상 그룹 헬스 간격 30초 × unhealthy 임계 3(`modules/alb` 가 넘기지 않아 provider 기본값) |
| 여유 | 30 | 표본 간격 하나 |

합은 **420초(7분)** 이고, 마지막 `poll_failed` 뒤로는 240초(120 + 90 + 30)를
더 봅니다. 설계의 「5분」 은 루프 시작을 기준으로 잡은 값이라 `startedAt`
기준으로는 짧습니다.

```shell
date -u +%s > "${evidence}/40-restart-at.txt"
aws ecs update-service --region "${region}" --cluster "${cluster}" \
  --service "${cluster}-clip" --force-new-deployment --output json \
  > "${evidence}/40-update-service.json"
deployment="$(jq -er '.service.deployments[] | select(.status == "PRIMARY") | .id' \
  "${evidence}/40-update-service.json")"
aws ecs describe-services --region "${region}" --cluster "${cluster}" \
  --services "${cluster}-clip" --query 'services[0].loadBalancers[].targetGroupArn' \
  --output json > "${evidence}/40-target-groups.json"
```

`update-service` 가 실패하거나 응답에 PRIMARY 배포가 없으면 `deployment` 가
빕니다. 이 값을 쓰는 함수 넷(`vpce_new_tasks` · `vpce_stopped_tasks` ·
`vpce_wait_rollout` · `vpce_rollout_summary`)은 머리의 `${deployment:?}` 에서
aws 를 부르지 않고 멈춥니다 — 빈 값으로 `list-tasks --started-by ""` 를 보내거나
멈춘 태스크 0건을 통과로 읽지 않게 합니다. 재기동 시각(`40-restart-at.txt`)은
`update-service` 앞에서 이미 쓰였으므로, 재기동을 다시 할 때는 이 블록을
처음부터 다시 돌려 시각을 새로 씁니다.

멈춘 새 태스크는 ECR · Secrets 실패의 직접 증거(`stoppedReason`)입니다. 새
태스크 대기(`vpce_wait_new_tasks`)와 판정이 함께 씁니다.
`list-tasks` 의 `--started-by` 는 다른 필터와 함께 쓸 수 없으므로(`aws ecs
list-tasks help` — 「it must be the only filter that you use」) clip 서비스의
`STOPPED` 태스크를 받은 뒤 `describe-tasks` 의 `startedBy` 로 이 배포의 것만
거릅니다. 사유는 `43-stopped.txt` 에 남습니다. 멈춘 태스크가 100개를 넘으면
`describe-tasks` 가 거부해 판정불가입니다(kty 회부).

```shell
vpce_stopped_tasks() {  # 이 배포의 멈춘 태스크 — 0건 0 · 있음 1 · 판정불가 2
  local list="${work}/stopped-tasks.json" out="${evidence}/43-stopped.txt" count
  : "${deployment:?}"
  if ! aws ecs list-tasks --region "${region}" --cluster "${cluster}" \
      --service-name "${cluster}-clip" --desired-status STOPPED --output json > "${list}"; then
    echo '멈춘 새 태스크: 판정불가 — list-tasks 실패'
    return 2
  fi
  : > "${out}"
  if ! vpce_describe_tasks "${list}" "${work}/stopped-described.json"; then
    echo '멈춘 새 태스크: 판정불가 — 목록 · describe-tasks 를 읽지 못했거나 결과가 목록과 다름'
    return 2
  fi
  if ! jq -r --arg d "${deployment}" '.tasks[] | select(.startedBy == $d)
      | "\(.taskArn | split("/") | last) \(.stoppedReason // "사유 없음")"' \
      "${work}/stopped-described.json" > "${out}"; then
    echo '멈춘 새 태스크: 판정불가 — describe-tasks 결과를 읽지 못함'
    return 2
  fi
  count="$(wc -l < "${out}" | tr -d ' ')"
  echo "멈춘 새 태스크: ${count}건"
  [ "${count}" -eq 0 ]
}
```

새 태스크를 기다리는 것은 `vpce_wait_new_tasks` 입니다. 15초마다 보되 재기동
명령 시각부터 **4분까지만** 기다립니다 — ECR 풀 · 비밀 주입 · awslogs 가
실패하면 태스크가 RUNNING 에 가지 못해(`startedAt` 없음) 목록이 계속 빕니다.
그동안 이 배포의 멈춘 태스크가 생기면 실패(1), 4분이 지나면 판정불가(2)입니다.
새 태스크가 뜨면 바로 표본을 받기 시작합니다 — 대상 상태는 지금 값만 읽히므로
나중에 거슬러 받을 수 없습니다. 태스크마다 따로 돌리고, 표본은 창 끝(또는
판정이 `대기` 로 알려 준 시각)까지 받습니다.

```shell
vpce_restart_at() {  # 재기동 명령 시각(epoch) — 기록이 없거나 숫자가 아니면 2
  local at
  at="$(cat "${evidence}/40-restart-at.txt" 2> /dev/null)"
  case "${at}" in
    '' | *[!0-9]*) return 2 ;;
  esac
  echo "${at}"
}

vpce_new_tasks() {  # 이 배포의 새 태스크마다 "<태스크 id> <startedAt epoch> <IP>"
  : "${deployment:?}"
  aws ecs list-tasks --region "${region}" --cluster "${cluster}" \
    --started-by "${deployment}" --output json > "${work}/new-tasks.json" || return 2
  vpce_describe_tasks "${work}/new-tasks.json" "${evidence}/42-new-tasks.json" || return 2
  jq -r "${vpce_jq_epoch}"' .tasks[] | select(.startedAt != null)
    | "\(.taskArn | split("/") | last) \(.startedAt | epoch) \(.attachments[].details[]
      | select(.name == "privateIPv4Address") | .value)"' "${evidence}/42-new-tasks.json" \
    || return 2
}

# 이 배포의 새 태스크가 기동하기를 15초마다 보되 재기동 명령 뒤 4분까지만
# 기다린다. 조회가 실패한 회차는 다음 회차에 다시 본다.
vpce_wait_new_tasks() {  # 기동 0 · 멈춘 새 태스크 1 · 4분 넘음 2 → 42-new-tasks.txt
  local list="${evidence}/42-new-tasks.txt" restart deadline rc
  if ! restart="$(vpce_restart_at)"; then
    echo '새 태스크: 판정불가 — 재기동 시각 기록 없음(40-restart-at.txt)'
    return 2
  fi
  deadline="$((restart + 240))"
  : > "${list}"
  while :; do
    if vpce_new_tasks > "${list}" && [ -s "${list}" ]; then
      cat "${list}"
      echo "새 태스크: $(grep -c . "${list}")개 기동"
      return 0
    fi
    : > "${list}"
    vpce_stopped_tasks > /dev/null
    rc=$?
    if [ "${rc}" -eq 1 ]; then
      echo '새 태스크: 실패 — 이 배포의 멈춘 태스크가 있음(사유는 43-stopped.txt)'
      return 1
    fi
    if [ "$(date -u +%s)" -ge "${deadline}" ]; then
      echo '새 태스크: 판정불가 — 재기동 4분 안에 기동한 새 태스크가 없음'
      return 2
    fi
    sleep 15
  done
}

# 표본을 30초마다 받고, 끝 시각 이상에서 찍힌 표본 하나를 받은 뒤 멈춘다 — 판정이
# 「마지막 표본 ≥ 끝」 을 요구하기 때문이다(끝 전에 멈추면 늘 「대기」).
vpce_sqs_sample() {  # vpce_sqs_sample <태스크 id> <IP> <끝 epoch>
  local out="${evidence}/50-sqs-$1.txt" tg state now
  tg="$(jq -r '.[0]' "${evidence}/40-target-groups.json")"
  while :; do
    now="$(date -u +%s)"
    state="$(aws elbv2 describe-target-health --region "${region}" --target-group-arn "${tg}" \
      --targets "Id=$2,Port=8081" --query 'TargetHealthDescriptions[0].TargetHealth.State' \
      --output text)" || state=error
    printf 'health %s %s\n' "${now}" "${state}" >> "${out}"
    [ "${now}" -lt "$3" ] || break
    sleep 30
  done
}

vpce_sqs_failures() {  # vpce_sqs_failures <태스크 id> <startedAt epoch> — poll_failed 시각을 덧붙임
  aws logs filter-log-events --region "${region}" --log-group-name "${cluster}-clip" \
    --log-stream-names "clip/clip/$1" --start-time "$(($2 * 1000))" \
    --filter-pattern '"broadcast.intake.poll_failed"' --output json \
    > "${work}/poll-failed-$1.json" || return 2
  jq -r '.events[] | "poll_failed \(.timestamp / 1000 | floor)"' "${work}/poll-failed-$1.json" \
    >> "${evidence}/50-sqs-$1.txt"
}
```

```shell
vpce_sqs_verdict() {  # vpce_sqs_verdict <startedAt epoch> <관찰 파일> — 통과 0 · 실패 1 · 판정불가 2 · 대기 3
  awk -v started="$1" -v base=420 -v after_fail=240 -v cap=900 '
    $1 == "health" {
      n++
      t[n] = $2
      s[n] = $3
      if ($3 == "error") { err = 1 }
    }
    $1 == "poll_failed" {
      fails++
      if ($2 > last_fail) { last_fail = $2 }
    }
    END {
      if (err) { print "SQS: 판정불가 — 대상 상태를 읽지 못한 표본이 있음"; exit 2 }
      for (i = 1; i <= n; i++) {
        if (s[i] == "healthy") {
          seen = 1
        } else if (seen) {
          printf "SQS: 실패 — healthy 뒤 %s(%d)\n", s[i], t[i]
          exit 1
        }
      }
      need = started + base
      if (fails && last_fail + after_fail > need) { need = last_fail + after_fail }
      if (need > started + cap) {
        printf "SQS: 판정불가 — 실패가 이어져 기동 뒤 %d초 안에 판정하지 못함\n", cap
        exit 2
      }
      if (n == 0 || t[n] < need) {
        printf "SQS: 대기 — 표본을 %d 까지 더 받음(poll_failed %d건)\n", need, fails
        exit 3
      }
      if (s[n] != "healthy") { print "SQS: 실패 — 창 끝에 healthy 아님"; exit 1 }
      printf "SQS: 통과 — poll_failed %d건 · 창 끝 healthy\n", fails
    }' "$2"
}

vpce_sqs_tasks() {  # 새 태스크 전부의 SQS 판정 — 통과 0 · 실패 1 · 판정불가 2 · 대기 3
  local task started total=0 rc worst=0
  while read -r task started _; do
    total=$((total + 1))
    if ! vpce_sqs_failures "${task}" "${started}" < /dev/null; then
      echo "SQS ${task}: 판정불가 — poll_failed 조회 실패"
      rc=2
    else
      vpce_sqs_verdict "${started}" "${evidence}/50-sqs-${task}.txt"
      rc=$?
    fi
    # 나쁜 쪽이 이긴다: 실패 1 > 판정불가 2 > 대기 3 > 통과 0.
    case "${worst}:${rc}" in
      1:* | *:0) ;;
      *:1 | 0:*) worst="${rc}" ;;
      3:2) worst=2 ;;
    esac
  done < "${evidence}/42-new-tasks.txt"
  if [ "${total}" -eq 0 ]; then
    echo 'SQS 판정: 판정불가 — 새 태스크 0개'
    return 2
  fi
  case "${worst}" in
    0) echo "SQS 판정: 통과 — 새 태스크 ${total}개" ;;
    1) echo 'SQS 판정: 실패' ;;
    2) echo 'SQS 판정: 판정불가' ;;
    3) echo 'SQS 판정: 대기' ;;
  esac
  return "${worst}"
}
```

```shell
vpce_wait_new_tasks
while read -r task started ip; do
  vpce_sqs_sample "${task}" "${ip}" "$((started + 420))" &
done < "${evidence}/42-new-tasks.txt"
wait
vpce_sqs_tasks
```

`vpce_wait_new_tasks` 가 0 이 아니면(멈춘 새 태스크 · 4분 넘음) 표본 줄은 빈
목록이라 돌지 않고, `vpce_sqs_tasks` 가 `판정불가 — 새 태스크 0개` 를 냅니다.
그대로 아래 「판정을 읽습니다」 블록으로 가서 배포 · 멈춘 태스크 · 로그 판정을
읽습니다.

`대기` 가 나오면 그 태스크만 `vpce_sqs_sample` 을 알려 준 시각까지 더 돌리고,
`poll_failed` 줄을 지운 뒤(`sed -i.bak '/^poll_failed /d'`) 다시
`vpce_sqs_failures` · `vpce_sqs_verdict` 합니다. 기다릴 끝(마지막 `poll_failed`
뒤 240초)이 기동 뒤 900초(15분)를 넘으면 `판정불가` 입니다 — 실패가 그만큼
이어지면 일시 실패가 아니므로 더 미루지 않습니다.

SQS 판정이 실패하면, 롤링이 판정보다 먼저 옛 태스크를 내리므로 운영 clip 의
수명 주기 수신이 끊긴 상태입니다. 바로 「되돌리기 1단계」 로 가고, DNS 를 끈 뒤
clip 을 다시 재기동해 NAT 로 회복시킵니다.

배포 · 로그 판정(1 · 2)의 함수입니다. `vpce_wait_rollout` 은 배포가
`COMPLETED`(0) · `FAILED`(1) 가 되기를 15초마다 보되 재기동 명령 시각부터
**10분까지만** 기다립니다. clip 서비스는 배포 회로 차단기(circuit breaker)를
켜지 않아 새 태스크가 계속 실패해도 `FAILED` 가 오지 않을 수 있습니다 — 10분이
지나면 판정불가(2)이고 확인 실패와 같이 「되돌리기 1단계」 회부로 갑니다. 조회가
실패한 회차는 다음 회차에 다시 봅니다.

```shell
vpce_wait_rollout() {  # 배포 끝 대기 — COMPLETED 0 · FAILED 1 · 재기동 10분 넘음 2
  local restart deadline state=''
  : "${deployment:?}"
  if ! restart="$(vpce_restart_at)"; then
    echo '배포: 판정불가 — 재기동 시각 기록 없음(40-restart-at.txt)'
    return 2
  fi
  deadline="$((restart + 600))"
  while :; do
    if aws ecs describe-services --region "${region}" --cluster "${cluster}" \
        --services "${cluster}-clip" --output json > "${evidence}/41-service.json"; then
      state="$(jq -r --arg d "${deployment}" '.services[0].deployments[]?
        | select(.id == $d) | .rolloutState' "${evidence}/41-service.json")"
      case "${state}" in
        COMPLETED) echo '배포: COMPLETED'; return 0 ;;
        FAILED) echo '배포: FAILED'; return 1 ;;
      esac
    fi
    if [ "$(date -u +%s)" -ge "${deadline}" ]; then
      echo "배포: 판정불가 — 재기동 10분 안에 끝나지 않음(마지막 ${state:-조회 실패}) · 되돌리기 회부"
      return 2
    fi
    sleep 15
  done
}

vpce_rollout_summary() {  # 41-service.json 의 이 배포 · 재기동 뒤 ECR · Secrets 오류 이벤트
  : "${deployment:?}"
  jq -r --arg d "${deployment}" --argjson since "$(cat "${evidence}/40-restart-at.txt")" \
    "${vpce_jq_epoch}"' .services[0]
    | (.deployments[] | select(.id == $d) | "rollout=\(.rolloutState) running=\(.runningCount) desired=\(.desiredCount)"),
      ([.events[] | select((.createdAt | epoch) >= $since)
        | select(.message | test("CannotPullContainerError|ResourceInitializationError"))]
        | "오류 이벤트 \(length)")' "${evidence}/41-service.json"
}

vpce_log_streams() {  # 새 태스크마다 로그 스트림이 재기동 뒤 생겼고 첫 이벤트가 기동 뒤 3분 안인가 — 통과 0 · 실패 1 · 판정불가 2
  local task started ip line rc worst=0 total=0
  if [ ! -s "${evidence}/42-new-tasks.txt" ]; then
    echo '로그 스트림: 판정불가 — 새 태스크 0개'
    return 2
  fi
  while read -r task started ip; do
    total=$((total + 1))
    # aws 가 실패하면 jq 는 빈 입력으로 줄 없이 0 을 낸다 — 빈 줄도 판정불가다.
    if ! line="$(aws logs describe-log-streams --region "${region}" --log-group-name "${cluster}-clip" \
        --log-stream-name-prefix "clip/clip/${task}" --output json < /dev/null \
      | jq -r --argjson since "$(cat "${evidence}/40-restart-at.txt")" --arg t "${task}" \
        --argjson started "${started}" \
        '.logStreams[0] // {} | "\($t) 생성 뒤=\((.creationTime // 0) / 1000 > $since) 기동 뒤 첫 이벤트 3분 안=\(((.firstEventTimestamp // 0) / 1000 - $started) <= 180 and (.firstEventTimestamp // 0) > 0)"')" \
        || [ -z "${line}" ]; then
      echo "로그 스트림 ${task}: 판정불가 — 조회 실패 또는 줄 없음"
      rc=2
    else
      echo "${line}"
      case "${line}" in
        *=false*) rc=1 ;;
        *) rc=0 ;;
      esac
    fi
    # 나쁜 쪽이 이긴다: 실패 1 > 판정불가 2 > 통과 0.
    case "${worst}:${rc}" in
      1:* | *:0) ;;
      *) worst="${rc}" ;;
    esac
  done < "${evidence}/42-new-tasks.txt"
  case "${worst}" in
    0) echo "로그 스트림: 통과 — 새 태스크 ${total}개" ;;
    1) echo '로그 스트림: 실패' ;;
    2) echo '로그 스트림: 판정불가' ;;
  esac
  return "${worst}"
}
```

판정을 읽습니다.

```shell
vpce_wait_rollout
vpce_rollout_summary
vpce_stopped_tasks
vpce_log_streams
```

기대는 `배포: COMPLETED` · `rollout=COMPLETED running=1 desired=1` · `오류 이벤트
0` · `멈춘 새 태스크: 0건` · 새 태스크마다 `생성 뒤=true 기동 뒤 첫 이벤트 3분
안=true` 와 마지막 줄 `로그 스트림: 통과 — 새 태스크 N개` 입니다.
`판정불가` · `실패` 가 하나라도 있으면 「확인의 판정과 기록」 으로 갑니다.

### 확인 (ㄹ) 회귀 감시

보조 신호입니다 — 판정은 (ㄱ)–(ㄷ) 가 합니다. 확인 창 동안 clip · auth · next ·
chat 두 서비스의 로그와 Media 상자의 사이드카 로그에 AWS 호출 오류 줄이
0 인지 봅니다. 일 큐 깊이 증가는 신호로 쓰지 않습니다(소비자가 아직 없어
증가가 곧 발행 성공입니다). 엔드포인트별 `NewConnections` 도 판정에 쓰지
않습니다 — 호출자 구분이 없고 (ㄴ) 탐침이 스스로 만듭니다.

```shell
vpce_error_pattern='?UnknownHostException ?SdkClientException'
vpce_error_pattern+=' ?ConnectTimeoutException ?"Unable to execute HTTP request"'
vpce_error_pattern+=' ?AccessDenied ?ENOTFOUND ?ETIMEDOUT'
vpce_error_pattern+=' ?"broadcast.intake.poll_failed"'
since_ms="$(( $(date -u +%s) * 1000 - 15 * 60 * 1000 ))"
for svc in clip auth next chat-collector chat-detector; do
  printf '%s %s\n' "${svc}" "$(aws logs filter-log-events --region "${region}" \
    --log-group-name "${cluster}-${svc}" --start-time "${since_ms}" \
    --filter-pattern "${vpce_error_pattern}" --query 'length(events)' --output text)"
done   # 다섯 줄 모두 0
```

Media 상자는 아래 스크립트를 `media_ssm` 으로 보냅니다(SSM 이라 실행 직전 kty
승인). 출력의 숫자가 0 이어야 합니다. journalctl 이 실패하면 숫자가 아닌 줄
(`journalctl 실패 — 판정불가`)과 종료 2 가 나옵니다 — 0건과 섞이지 않게
`grep -c` 의 「0건이면 종료 1」 은 삼키고 journalctl 의 종료 코드는 따로
봅니다.

```shell
vpce_sidecar_script() {  # 상자에 보낼 사이드카 오류 세기 스크립트(sh)를 낸다
  cat <<'SH'
pattern='no such host|i/o timeout|dial tcp|AccessDenied'
if ! log="$(journalctl -q -t pokeclip-media-sidecar --since '-15 min' -o cat --no-pager)"; then
  echo 'journalctl 실패 — 판정불가'
  exit 2
fi
printf '%s\n' "${log}" | grep -cE "${pattern}" || :
SH
}
```

```shell
vpce_sidecar_script > "${work}/vpce-sidecar-errors.sh"
media_ssm "$(vpce_media_box)" "${work}/vpce-sidecar-errors.sh"
```

### 확인의 판정과 기록

(ㄱ) · (ㄴ) · (ㄷ) 중 하나라도 실패하면 「되돌리기 1단계」 를 kty 에게
회부합니다. kty 가 조건부 사전 승인을 줬으면(설계 7절 Q2 — 그 apply 부터 확인이
끝날 때까지 한 번) 바로 1단계로 갑니다. (ㄷ) 의 재기동 전 관문에서 멈춘 것은
네트워크 실패가 아니므로 되돌리기가 아니라 kty 회부입니다.

판정불가는 통과가 아닙니다. (ㄷ) 의 판정불가(새 태스크 미기동 · 배포 10분 넘음 ·
새 태스크 0개 · SQS 15분 넘음)는 kty 에게 「되돌리기 1단계」 를 제안해
회부합니다. 사전 승인(Q2)이 있어도 바로 1단계로 가지 않습니다 — 판정불가는
사전 승인 대상이 아닙니다. 노트북 쪽 조회가 실패해 난 (ㄷ) 의 판정불가(SQS
대상 상태를 읽지 못한 표본 · `poll_failed` 조회 실패 · 멈춘 태스크 `list-tasks`
실패나 100개 초과 · 재기동 전 비밀 관문의 조회 실패)는 먼저 다시 조회하고,
그래도 안 되면 kty 에게 회부합니다 — SQS 상태가 미확인이면 되돌리기 1단계를
권합니다.
(ㄴ) 의 판정불가(상자 키 · 계정 · SSM 명령 실패)는 이름을 보지 못한 것이라
되돌리기가 아니라 kty 회부입니다 — Media 상자의 SSM 은 NAT 로 가므로
엔드포인트 장애의 증거가 아닙니다. 사전 승인(Q2)은 「실패」 에만 씁니다.

다 통과하면 판정 줄(가린 것)을 PR-N 에 남기고 PR 488 로 넘어갑니다. 그렇게
확인을 마치면 여기서 작업 디렉터리를 지웁니다 — plan 파일에 계정 ID 가 든 ARN
이 있습니다. 증거 디렉터리는 남깁니다. 되돌리기로 가면 1단계가 `work` 를
쓰므로 「되돌리기 정리」 에서 지웁니다.

```shell
rm -r -- "${work:?}" && unset work
```

## 되돌리기

이 PR 묶음에서 쓰는 되돌리기는 1단계(private DNS 끄기)입니다. 2단계(엔드포인트
철거)는 단계마다 관문이 있고 [prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md)
「인터페이스 엔드포인트」 가 이리로 옵니다. 되돌리기 apply 는 network 쓰기라
kty 승인입니다.

### 되돌리기 판정 함수

되돌리기는 확인과 다른 날 · 다른 셸에서 할 수 있습니다. 그때는 「준비」 의
블록과 이 절의 함수 블록을 **먼저** 읽습니다. 「준비」 는 증거 디렉터리를 새로
만들므로, 앞선 기록(`70-calibration.jsonl` · `80-dns-off-at.txt`)을 이어
쓰려면 그다음에 `evidence` 를 그 디렉터리로 다시 둡니다.

`vpce_plan_changes` 는 plan 의 `show -json` 을 받아 바뀌는 자원마다 「<동작>
<주소> <바뀌는 속성>」 을 냅니다. 새로 만드는 자원은 `before` 가 null 이라 빈
객체로 읽습니다.

```shell
vpce_plan_changes() {  # terraform show -json <plan> | vpce_plan_changes
  jq -r '.resource_changes[] | select(.change.actions != ["no-op"])
      | . as $c | ($c.change.before // {}) as $b | ($c.change.after // {}) as $a
      | [($c.change.after_unknown // {} | to_entries[] | select(.value == true) | .key),
        (($b | keys) + ($a | keys) | unique[] | select($b[.] != $a[.]))]
      | unique | "\($c.change.actions | join(",")) \($c.address) \(join(","))"' \
    | sort
}
```

```shell
vpce_ref_gate() {  # vpce_ref_gate <엔드포인트 SG id> — 통과 0 · 멈춤 1 · 판정불가 2
  local rules="${evidence}/60-sg-rules.json" refs
  if ! aws ec2 describe-security-group-rules --region "${region}" --output json \
      > "${rules}"; then
    echo '참조 관문: 판정불가 — describe-security-group-rules 실패'
    return 2
  fi
  # 양성 대조 — 엔드포인트 SG 자신의 인그레스(출처 CIDR)가 결과에 있어야 한다.
  if ! jq -e --arg sg "$1" '.SecurityGroupRules | type == "array"
      and any(.GroupId == $sg and .IsEgress == false and .CidrIpv4 != null)' \
      "${rules}" > /dev/null; then
    echo '참조 관문: 판정불가 — 엔드포인트 SG 자신의 인그레스가 결과에 없음'
    return 2
  fi
  # 참조 행 = 다른 SG 규칙의 대상(ReferencedGroupInfo)이 엔드포인트 SG 인 행.
  # 엔드포인트 SG 자신의 인그레스는 출처가 CIDR 이라 섞이지 않는다.
  if ! jq -r --arg sg "$1" '.SecurityGroupRules[]
      | select(.ReferencedGroupInfo.GroupId == $sg)
      | "참조 \(.GroupId) \(if .IsEgress then "이그레스" else "인그레스" end) \(.FromPort)"' \
      "${rules}" > "${evidence}/60-sg-refs.txt"; then
    echo '참조 관문: 판정불가 — 규칙 읽기 실패'
    return 2
  fi
  refs="$(grep -c . "${evidence}/60-sg-refs.txt")"
  if [ "${refs}" -ne 0 ]; then
    cat "${evidence}/60-sg-refs.txt"
    echo "참조 관문: 멈춤 — 참조 ${refs}건"
    return 1
  fi
  echo '참조 관문: 통과 — 참조 0'
}
```

`vpce_ref_gate` 의 엔드포인트 SG id 는 `terraform -chdir="${network}" output
-raw interface_endpoint_sg_id` 입니다. 지표 관문은 `BytesProcessed` 가 `Endpoint
Type` · `Service Name` · `VPC Endpoint Id` · `VPC Id` 차원 조합으로 게시되고,
CloudWatch 가 게시되지 않은 조합을 물으면 오류 대신 빈 결과를 준다는 점을
막습니다. 그래서 `list-metrics` 로 실제 차원 집합을 받아 그 집합으로 양성
대조(데이터 점 있음)를 한 것만 교정으로 치고, 판정은 그 집합 그대로 묻습니다.
교정 기록은 `70-calibration.jsonl`(엔드포인트마다 id · 차원 · 창 · 주기 60 ·
통계 Sum)입니다.

```shell
vpce_metric_calibrate() {  # vpce_metric_calibrate <시작 ISO> <끝 ISO> — 성공 0 · 판정불가 2
  local list="${evidence}/70-list-metrics.json" cal="${evidence}/70-calibration.jsonl"
  local id dims
  : > "${cal}"
  if ! jq -e '.VpcEndpoints | length == 5' "${evidence}/10-endpoints.json" > /dev/null 2>&1; then
    echo '교정: 판정불가 — 엔드포인트 기록(vpce_endpoints)이 다섯이 아님'
    return 2
  fi
  if ! aws cloudwatch list-metrics --region "${region}" --namespace AWS/PrivateLinkEndpoints \
      --metric-name BytesProcessed --output json > "${list}"; then
    echo '교정: 판정불가 — list-metrics 실패'
    return 2
  fi
  for id in $(jq -r '.VpcEndpoints[].VpcEndpointId' "${evidence}/10-endpoints.json"); do
    dims="$(jq -c --arg id "${id}" \
      --argjson want '["Endpoint Type", "Service Name", "VPC Endpoint Id", "VPC Id"]' \
      '[.Metrics[].Dimensions | select(any(.Name == "VPC Endpoint Id" and .Value == $id))
        | select(map(.Name) | sort == $want)]
      | if length == 1 then .[0] else empty end' "${list}")"
    if [ -z "${dims}" ]; then
      echo "교정: 판정불가 — ${id} 의 차원 집합이 하나가 아님"
      return 2
    fi
    if ! aws cloudwatch get-metric-statistics --region "${region}" \
        --namespace AWS/PrivateLinkEndpoints --metric-name BytesProcessed \
        --dimensions "${dims}" --start-time "$1" --end-time "$2" --period 60 \
        --statistics Sum --output json > "${work}/calibrate-${id}.json"; then
      echo "교정: 판정불가 — ${id} 조회 실패"
      return 2
    fi
    if ! jq -e '(.Datapoints | length) > 0' "${work}/calibrate-${id}.json" > /dev/null; then
      echo "교정: 판정불가 — ${id} 에 그 창의 데이터 점이 없음(양성 대조 실패)"
      return 2
    fi
    jq -nc --arg id "${id}" --argjson dims "${dims}" --arg s "$1" --arg e "$2" \
      '{id: $id, dimensions: $dims, start: $s, end: $e, period: 60, statistic: "Sum"}' \
      >> "${cal}"
  done
  echo '교정: 성공 — 다섯 모두 데이터 점 있음'
}

vpce_metric_judge() {  # vpce_metric_judge <시작 ISO> <끝 ISO> — 통과 0 · 멈춤 1 · 판정불가 2
  local cal="${evidence}/70-calibration.jsonl" busy=0 line id dims
  if [ "$(grep -c . "${cal}" 2> /dev/null)" != 5 ]; then
    echo '트래픽 관문: 판정불가 — 교정 기록이 없거나 다섯이 아님'
    return 2
  fi
  while IFS= read -r line; do
    id="$(printf '%s' "${line}" | jq -r .id)"
    dims="$(printf '%s' "${line}" | jq -c .dimensions)"
    if ! aws cloudwatch get-metric-statistics --region "${region}" \
        --namespace AWS/PrivateLinkEndpoints --metric-name BytesProcessed \
        --dimensions "${dims}" --start-time "$1" --end-time "$2" --period 60 \
        --statistics Sum --output json > "${work}/judge-${id}.json" < /dev/null \
      || ! jq -e '.Datapoints | type == "array"' "${work}/judge-${id}.json" > /dev/null; then
      echo "트래픽 관문: 판정불가 — ${id} 조회 실패"
      return 2
    fi
    if jq -e '([.Datapoints[].Sum] | add // 0) > 0' "${work}/judge-${id}.json" > /dev/null; then
      echo "트래픽 ${id}: $(jq '[.Datapoints[].Sum] | add' "${work}/judge-${id}.json") 바이트"
      busy=1
    fi
  done < "${cal}"
  if [ "${busy}" -ne 0 ]; then
    echo '트래픽 관문: 멈춤 — 트래픽이 남음((다) 로)'
    return 1
  fi
  echo '트래픽 관문: 통과 — 다섯 모두 합 0 또는 데이터 점 없음'
}
```

### 되돌리기 1단계 — private DNS 끄기

1.  render 가 엔드포인트 이그레스로 떠 있으면 먼저 긴급 정지하고(workers
    README 「긴급 정지와 상태 어긋남」), 기록한 태스크가 모두 `STOPPED` 인지
    봅니다(runtime-1a 의 `wait_stopped` 와 같은 확인). render 이그레스엔 NAT
    길이 없어 DNS 를 끄면 AWS 에 닿지 못합니다. PR 488 전이면 render 가 없어
    건너뜁니다. 2단계 1 뒤에 부를 때도 render 가 이미 NAT 이그레스라
    건너뜁니다.
1.  **교정(2단계 삭제 관문 (나) 의 앞몫 — 트래픽이 있는 지금 합니다).**
    `vpce_endpoints` 를 돌린 뒤 지난 60분으로 교정합니다. 교정이 판정불가여도
    1단계는 멈추지 않습니다 — 2단계에서 DNS 를 끈 시각 직전 60분으로 다시
    교정할 수 있습니다(1분 해상도 점은 15일 보존).

    ```shell
    vpce_endpoints
    vpce_metric_calibrate "$(jq -rn 'now - 3600 | todate')" "$(jq -rn 'now | todate')"
    ```

1.  `terraform.tfvars` 에 `interface_endpoint_private_dns = false` 한 줄을 넣고
    plan 합니다. 요지는 엔드포인트 다섯의 `update` 이고, 바뀌는 속성은
    `private_dns_enabled` 하나여야 합니다. 다르면 사전 승인이 무효이고 kty
    에게 넘깁니다.

    ```shell
    terraform -chdir="${network}" plan -input=false -out="${work}/dns-off.tfplan" \
      > "${evidence}/80-plan.txt"
    terraform -chdir="${network}" show -json "${work}/dns-off.tfplan" \
      | vpce_plan_changes | tee "${evidence}/80-plan-summary.txt"
    ```

    기대는 다섯 줄 `update module.network.aws_vpc_endpoint.interface["<서비스>"]
    private_dns_enabled` 입니다.
1.  kty 가 apply 합니다. 끝난 시각을 남깁니다 — 2단계의 교정 · 판정 창이 이
    시각을 씁니다.

    ```shell
    date -u +%Y-%m-%dT%H:%M:%SZ > "${evidence}/80-dns-off-at.txt"
    ```

1.  **복구 완료 조건 — 셋 다 만족해야 복구입니다.**
    *   (가) Media 상자에서 다섯 이름이 ENI 밖(공용) 주소로 풀립니다 — 확인
        (ㄴ) 의 블록을 돌리되 마지막 줄을 `vpce_resolve off
        "${evidence}/20-resolve-off.txt"` 로 바꿉니다.
    *   (나) clip 강제 재기동이 NAT 경로로 성공합니다 — 확인 (ㄷ) 와 같은 재기동
        전 비밀 관문 · 같은 판정. 다시 돌리기 전에 앞선 확인의 30–50번 증거를
        회차 디렉터리(`try-<UTC 시각>/`)로 옮깁니다 — 같은 이름으로 덮어써
        실패 증거가 사라지지 않게 합니다(`70-` · `80-` 은 그대로 둡니다).
        셸 글롭 대신 `find` 로 골라 bash · zsh 모두 옮길 파일이 없어도 오류
        없이 지나갑니다. 회차 디렉터리를 만들지 못하면 옮기지 않습니다.

        ```shell
        try_dir="${evidence}/try-$(date -u +%Y%m%dT%H%M%SZ)"
        mkdir -- "${try_dir}" && find "${evidence}" -maxdepth 1 -type f \
          -name '[345][0-9]-*' -exec mv -- {} "${try_dir}/" \;
        ```

    *   (다) 그 뒤 15분 동안 확인 (ㄹ) 의 오류 줄이 0 으로 떨어집니다. 캐시된 ENI
        주소나 기존 연결로 가는 호출은 엔드포인트 SG 가 원인인 장애라면 계속
        실패할 수 있습니다. 캐시 · 연결이 만료돼 다시 풀 때 공용으로 넘어가므로,
        15분 안에 0 이 되는지가 복구 판정입니다. 0 이 되지 않으면 kty 에게
        회부합니다(그 서비스 강제 재기동 등).

### 다시 켜기

1단계 뒤 원인을 고쳐 다시 켤 때입니다. 켜는 것은 처음 apply 와 같은 VPC 전역
전환이라 확인도 처음과 같습니다.

1.  원인을 고친 PR 이 있으면 머지합니다(엔드포인트 SG · 모듈 등).
1.  `terraform.tfvars` 의 `interface_endpoint_private_dns = false` 줄을 지우고
    plan 합니다. 요지는 1단계 3 과 같은 `vpce_plan_changes` 로 다섯 줄 `update …
    private_dns_enabled` 이어야 합니다.
1.  kty 승인으로 apply 하고 「확인 (ㄱ)」 부터 「확인의 판정과 기록」 까지를
    그대로 다시 돕니다. 실패하면 다시 1단계입니다(사전 승인은 새로 받습니다 —
    처음 것은 한 번 쓰면 끝). 다시 돌기 전에 1단계 (나) 의 회복 증거(30–50번)를
    1단계 (나) 와 같은 블록으로 새 회차 디렉터리에 옮깁니다.

    ```shell
    try_dir="${evidence}/try-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -- "${try_dir}" && find "${evidence}" -maxdepth 1 -type f \
      -name '[345][0-9]-*' -exec mv -- {} "${try_dir}/" \;
    ```

1.  workers 를 `render_egress_via_endpoints = false` 로 둔 채였으면, 확인을 지난
    뒤 workers 를 기본값(true)으로 되돌리는 apply 는 workers README 의 절차를
    따릅니다.

### 되돌리기 2단계 — 엔드포인트 철거

엔드포인트를 지우는 길입니다. 순서를 지키고 단계마다 관문을 지납니다.

1.  render 가 엔드포인트 SG 를 쓰지 않게 합니다 — workers 를
    `render_egress_via_endpoints = false` 로 apply(render 이그레스가 NAT 로
    돌아가 엔드포인트 SG 참조가 사라짐)하거나 workers 를 철거합니다. 이 걸음은
    DNS 가 꺼진 동안에도 workers 계약 검사에 막히지 않습니다. workers apply 는
    원하는 수 1 을 되살려 긴급 정지한 render 가 NAT 이그레스로 다시 뜹니다 —
    다시 띄우기는 매번 kty 승인이며 이 걸음의 승인이 그것을 겸한다는 것을 승인
    요청에 적습니다. 다시 띄우지 않으려면 apply 직후 긴급 정지를 함께
    승인받습니다.
1.  **교정이 없으면 DNS 를 끄기 전에 합니다.** 1단계 2 의 교정 기록
    (`70-calibration.jsonl`)이 없으면 지금 트래픽이 있을 때 교정합니다.
    *   DNS 를 아직 끄지 않았으면 1단계 2 의 블록(지난 60분)으로 교정합니다.
        `ecr.api` · `ecr.dkr` · `secretsmanager` 는 태스크가 뜰 때만 트래픽이
        있어, 지난 60분에 기동이 없었으면 그 셋이 비어 판정불가입니다. 그때는
        교정 전에 clip 을 재기동해 다섯 경로에 트래픽을 만듭니다 — 확인 (ㄷ) 의
        재기동 전 비밀 관문 · 재기동 블록 그대로(kty 승인)이고, 배포가
        `COMPLETED` 가 된 뒤 교정합니다. 데이터 점이 아직 게시되지 않았으면
        판정불가이니 몇 분 뒤 다시 교정합니다.
    *   DNS 가 이미 꺼져 있으면 끈 시각 직전 60분으로 교정합니다(끈 지 15일
        안 · 아래 블록 — `80-dns-off-at.txt` 를 읽습니다). 이때의 재기동은 NAT
        로 가 엔드포인트 트래픽을 만들지 못합니다.

    ```shell
    off_at="$(cat "${evidence}/80-dns-off-at.txt")"
    vpce_metric_calibrate "$(jq -rn --arg t "${off_at}" '$t | fromdateiso8601 - 3600 | todate')" \
      "${off_at}"
    ```

1.  1단계가 아직이면 1단계(DNS 끄기)와 그 복구 완료 조건을 먼저 지납니다. DNS
    를 켠 채 엔드포인트를 지우면 캐시 · 연결이 지워진 ENI 로 가는 실패 창이
    platform · media 전체에 생깁니다.
1.  **삭제 관문 — 둘 다 통과해야 지웁니다. 판정불가는 통과가 아닙니다.**
    *   (가) 참조 0 — `vpce_ref_gate`(「되돌리기 판정 함수」).
    *   (나) 트래픽 0 — DNS 를 끈 뒤 60분이 지난 다음, 지난 60분을 교정과 같은
        차원 집합 · 같은 명령으로 `vpce_metric_judge` 합니다.
    *   (다) 풀리지 않을 때의 출구 — (나) 에 트래픽이 남으면 DNS 를 끈 뒤에도
        남은 ENI 로 성공하는 기존 연결(SDK 연결 풀 · 긴 폴링)이 있다는
        뜻입니다. VPC 호출자(platform 다섯 · Media 사이드카 · sql_runner)를
        재기동해 연결을 다시 맺게 한 뒤(각각 kty 승인 · 재기동 전 비밀 관문
        같음) 60분을 다시 잽니다.
    *   판정불가가 이어지거나(트래픽이 없던 엔드포인트는 양성 대조가 없어
        교정이 안 됩니다 — DNS 를 끄기 전이면 2 의 재기동 출구를 먼저 씁니다)
        (다) 를 kty 가 원하지 않으면, kty 에게 회부하고 **ENI 를 보존합니다** —
        엔드포인트를 지우지 않고 DNS 끈 상태로 둡니다(비용은 남지만 장애는
        없습니다).
1.  두 관문을 지난 뒤에만 `main.tf` 의 `interface_endpoint_services` 를 `[]`
    로 바꾼 PR(또는 PR-N 의 revert)을 머지하고 network apply 합니다(kty 승인 ·
    plan 요지 = 「적용 1」 의 일곱 줄이 모두 `delete`).

판정 창은 DNS 를 끈 지 60분이 지난 뒤의 지난 60분입니다.

```shell
vpce_metric_judge "$(jq -rn 'now - 3600 | todate')" "$(jq -rn 'now | todate')"
```

#### 순서를 어기면

render 가 엔드포인트 SG 를 참조하는 채 `[]` 를 apply 하면 「막힌다」 가
아닙니다. `depends_on` 순서대로 엔드포인트 다섯과 인그레스 규칙이 **먼저
지워져** render 는 그 순간 AWS 에 닿지 못하고, SG 삭제만 `DependencyViolation`
으로 시한까지 다시 시도하다 실패해 network 가 「엔드포인트 없음 · SG 남음」 으로
반쯤 적용됩니다. 그래서 삭제 관문 (가) 를 둡니다.

### 되돌리기 정리

이 셸에서 할 되돌리기(1단계 · 다시 켜기 · 2단계)가 끝나면 작업 디렉터리를
지웁니다 — plan 파일에 계정 ID 가 든 ARN 이 있습니다. 증거 디렉터리는 남깁니다.

```shell
rm -r -- "${work:?}" && unset work
```

## See also

*   [prod 철거 런북](/infra/terraform/runbooks/prod-teardown.md) — 「plan 요지」 ·
    「인터페이스 엔드포인트」
*   [Media 교체 런북](/infra/terraform/runbooks/media-swap.md) — 「준비」(SSM
    명령 보내기)
*   [단계 직전 점검 런북](/infra/terraform/runbooks/stage-precheck.md) — 「증거」
*   `scripts/test/network_vpce_runbook_test.sh` — 이 README 의 판정 함수 시험
