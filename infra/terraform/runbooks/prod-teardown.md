# prod 철거 런북

apply 로 만든 `prod/media` · `prod/platform` 자원을 내리는(destroy) 절차입니다.
M9 에서 `prod/registry` 에 더한 저장소 둘(`media-host-bundle` ·
`media-loopback-sender`)도 필요하면 함께 내립니다. 두 루트를 apply 한 뒤 그
코드를 되돌리거나(revert), O7d · O7e 를 처음부터 다시 할 때 씁니다.

*   실행자는 kty 입니다. **kty 로컬 터미널**에서 kty IAM 사용자 프로필로
    돌립니다. plan · 상태 · 조회 출력에 계정 ID 가 든 ARN 이 섞여 에이전트
    세션이나 세션의 `!` 로 돌리지 않고, 출력을 세션 · 문서 · 채팅에 붙여
    넣지 않습니다.
*   **상태 떼기(`state rm`) · destroy · 이미지 삭제 · 파라미터 삭제마다
    실행 직전에 kty 가 승인합니다.** destroy 는 plan 요지(아래 「plan
    요지」)를 kty 가 본 뒤에만 합니다.
*   destroy 가 `AccessDenied` 로 실패하면 계획 3절 「SCP 멈춤 규칙」 을
    따릅니다 — 다시 시도하지 않고 kty 에게 보고합니다.

용어:

*   **plan 요지**: destroy plan 이 지우는 관리 자원 목록입니다. 모든 줄이
    `delete` 이고 그 루트의 자원만 있는지 봅니다.
*   **거부 목록 파라미터**: 응답기(M15)가 읽을 SSM 파라미터
    `/pokeclip/prod/media/ingest-auth-deny-tokens` 입니다
    (`modules/media_host/denylist.tf` — 이름은 모듈 기본값이고 `prod/media`
    가 덮지 않습니다). 값은 운영자가 고칩니다.
*   **잔여 0**: 철거한 루트의 자원이 AWS 에 하나도 남지 않은 상태입니다.
    태그(`TerraformRoot`)와 이름 접두(`pokeclip-prod-`)로 조회합니다.

## 지키는 순서

| 순서 | 무엇 | 왜 |
|---|---|---|
| 1 | **revert 전에** 내린다(platform · media) | revert 하면 루트 코드가 사라져 destroy 할 구성이 없어집니다. 상태만 남고 자원은 관리 밖에 남습니다. registry 저장소 둘은 예외입니다 — 순서 4 |
| 2 | platform 출력을 읽는 다른 루트가 먼저 | edge · records · 런타임 2단 · 일꾼 · chat 이 platform 출력을 씁니다(platform README 「출력」). 이 런북은 그 루트들을 내리지 않습니다 — 아래 「시작 전」 4 |
| 3 | `prod/media` 다음 `prod/platform` | `prod/media/remote_state.tf` 가 platform 상태를 읽고, `main.tf` 가 대상 그룹 ARN · LB SG · nginx 포트를 그 출력에서 받습니다. platform 을 먼저 내리면 media 의 destroy plan 이 출력 이름으로 실패합니다. Media SG 규칙도 platform 의 LB SG 를 출처로 가리킵니다 |
| 4 | registry 저장소 둘은 맨 끝 · 필요할 때만 · **revert 뒤가 기본** | 두 저장소의 이미지는 `prod/media` 만 씁니다. `prod/registry` 루트는 revert 뒤에도 남고 두 키만 빠지므로, revert 뒤의 registry apply 로 지우는 것이 기본입니다(「4. registry 저장소 둘」) |

앞 루트(network · certs · secrets · registry 의 저장소 11개 · 런타임 1단 ·
bootstrap)는 이 런북이 내리지 않습니다. platform · media 의 destroy plan 이
그 상태 출력을 읽으므로, 철거가 끝날 때까지 그대로 둡니다.

## 승인 지점

| 지점 | 언제 | kty 가 보는 것 |
|---|---|---|
| T1 | 시작 | 리전 한 값(「시작 전」 2) · 의존 루트 확인(「시작 전」 4) · 방송 없음 · 거부 목록 파라미터 갈래 (a) · (b) 선택((b) 면 목록 토큰 회수 확인) |
| T2 | 상태 떼기 | `state rm -dry-run` 출력 한 줄 |
| T3 | media destroy | plan 요지 |
| T4 | platform destroy | plan 요지 |
| T5 | registry 갈래 | 지울 이미지 수 · plan 요지 |
| T6 | 갈래 (b) 의 파라미터 삭제 | 값 보관 파일이 있음 |

## 시작 전

1.  apply 한 커밋(revert 전)의 체크아웃 저장소 뿌리에서 돌립니다. 루트마다
    `backend.hcl` 로 `init -backend-config=backend.hcl` 한 상태여야 하고,
    apply 때 쓴 `terraform.tfvars` 가 있어야 합니다 — destroy 도 기본값
    없는 변수(상자 키 · 다이제스트 등)를 요구합니다.
1.  작업 디렉터리(plan 파일 — 계정 ID 가 든 ARN 이 있음)는 저장소 밖에,
    결과 원문은 증거 루트 `~/.pokeclip-precheck/` 아래 이 철거 전용
    디렉터리(0700)에 둡니다. 증거 규칙은
    [단계 직전 점검 런북](stage-precheck.md) 「증거」 입니다.

    ```shell
    umask 077
    work="$(mktemp -d "${TMPDIR:-/tmp}/pokeclip-teardown.XXXXXX")"
    evidence="${HOME}/.pokeclip-precheck/teardown-prod-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -p "${evidence}" && chmod 700 "${evidence}"
    media=infra/terraform/prod/media
    platform=infra/terraform/prod/platform
    registry=infra/terraform/prod/registry
    state_bucket=pokeclip-tfstate-prod-apne2
    ```

    **리전 고정.** apply 에 쓴 리전(루트마다 `var.region` — tfvars 가 덮지
    않으면 기본값)을 세 루트에서 읽어, 한 값이면 CLI 의 기본 리전으로 둡니다.
    아래 리전별 aws 명령(ec2 · elbv2 · ecs · Cloud Map · logs · ssm · ecr ·
    wafv2 · 태그 조회)은 모두 `--region "${region}"` 을 붙입니다 — 프로필의
    기본 리전이 다르면 같은 이름의 다른 리전 자원을 지우거나 셉니다.
    `terraform console` 은 읽는 동안 상태를 잠급니다(바로 풀림).

    ```shell
    umask 077
    for root in "${media}" "${platform}" "${registry}"; do
      echo "$(basename "${root}") $(echo 'var.region' \
        | terraform -chdir="${root}" console | tr -d '"')"
    done | tee "${evidence}/00-region.txt"
    cut -d' ' -f2 "${evidence}/00-region.txt" | sort -u | wc -l   # 1
    region="$(cut -d' ' -f2 "${evidence}/00-region.txt" | sort -u)"
    export AWS_REGION="${region}" AWS_DEFAULT_REGION="${region}"
    ```

    `1` 이 아니면(루트마다 리전이 다르거나 비었으면) 멈추고 kty 에게
    보고합니다. 셸을 새로 열면 이 블록과 위 변수 블록을 다시 둡니다.

1.  호출 주체가 리스 계정의 kty IAM 사용자인지 `aws sts get-caller-identity`
    로 눈으로 봅니다(값은 파일 · 보고에 옮기지 않음). 단계 직전 점검에는
    철거 단계의 묶음이 아직 없습니다 — 삭제 동작을 미리 판정할 묶음이
    필요한지는 O7d · O7e 때 확인합니다.
1.  **의존 루트 확인.** 상태 버킷의 prod 상태 키를 봅니다(읽기).

    ```shell
    umask 077
    aws s3api list-objects-v2 --bucket "${state_bucket}" --prefix prod/ \
      --query 'Contents[].Key' --output text | tr '\t' '\n' \
      | tee "${evidence}/00-state-keys.txt"
    roots='bootstrap|network|certs|secrets|registry|runtime|platform|media'
    grep -Ev "^prod/(${roots})/terraform\.tfstate(\.tflock)?$" \
      "${evidence}/00-state-keys.txt"   # 출력 없음
    ```

    알려진 여덟 루트의 상태 키(`prod/<루트>/terraform.tfstate` 와 그 잠금
    파일 `.tflock`) 밖의 키가 하나라도 나오면 멈추고 kty 에게 보고합니다.
    접두가 아니라 키 전체로 봅니다 — 런타임 2단 · edge · records 의 키가
    `prod/runtime/` 같은 앞 루트 디렉터리 아래에 있어도 걸리게. 그 루트에 자원이 남았으면 그 루트를 먼저 내려야 하고, 그
    절차는 이 런북에 없습니다(O7d · O7e 때 확인).
1.  방송이 없는지 kty 가 확인합니다. media destroy 는 상자를 종료합니다 —
    상자 디스크의 녹화 · DVR 은 함께 사라집니다(`delete_on_termination`).
1.  시작 전 상태를 남깁니다.

    ```shell
    umask 077
    for root in "${media}" "${platform}"; do
      terraform -chdir="${root}" state list > "${evidence}/01-$(basename "${root}")-state.txt"
    done
    wc -l "${evidence}"/01-*-state.txt
    ```

### plan 요지

destroy 마다 plan 파일을 만들고 요지를 남깁니다. 판정은 둘입니다 —
`delete` 가 아닌 줄 0, 그 루트 밖 주소 0. 하나라도 어긋나면 apply 하지
않고 kty 에게 보고합니다.

```shell
plan_summary() {  # plan_summary <루트> <plan 파일>
  terraform -chdir="$1" show -json "$2" \
    | jq -r '.resource_changes[] | select(.mode == "managed")
        | select(.change.actions != ["no-op"])
        | "\(.change.actions | join(",")) \(.address)"'
}
```

## 1. 거부 목록 파라미터 — 갈래 고르기

이 파라미터는 `lifecycle { prevent_destroy = true }` 입니다. 상태에 있는
채로는 media 의 destroy plan 이 그 자원 주소로 실패합니다. 코드에서
`prevent_destroy` 를 빼는 길은 쓰지 않습니다 — guard R10
(`policy/guard/prevent_destroy.rego`)이 리터럴 `true` 를 강제해 infra-ci 가
막습니다. 그래서 두 갈래 모두 **상태에서 먼저 떼고** destroy 합니다.

| 갈래 | 고를 때 | 끝난 뒤 |
|---|---|---|
| (a) 떼고 남기기 | `prod/media` 를 다시 apply 할 예정이고 운영자가 고친 목록을 이어 쓸 때 · 지울지 아직 정하지 못했을 때(나중에 (b) 의 삭제 명령으로 지울 수 있음) | 파라미터가 AWS 에 관리 밖으로 남습니다. 다시 apply 하기 전에 import 합니다 |
| (b) 남김 없이 지우기 | 다시 apply 하지 않거나 목록을 이어 쓸 필요가 없다고 kty 가 정했고, **목록의 토큰이 모두 auth 에서 회수(revoked)됐음**을 kty 가 확인했을 때. 하나라도 확인하지 못하면 (a) | 파라미터가 없습니다. 값은 증거 디렉터리에만 남습니다. 다시 apply 하면 apply **전에** 값을 되살립니다(「6. (b) 파라미터 삭제」 의 「(b) 뒤 다시 apply」) |

(b) 에 회수 확인을 거는 까닭: 파라미터가 없는 채 `prod/media` 를 다시
apply 하면 목록이 머리 줄(`# pokeclip deny-tokens v1`)만으로 새로 생기고,
응답기는 빈 목록으로 뜹니다. 응답기 계약 D7 의 경고 그대로 「거부 목록
파일이 사라진 채 응답기가 다시 뜨면 거부했던 토큰이 신호 없이 다시
붙는다」([응답기 계약](../../../media/cmd/ingest-auth/CONTRACT.md) 「D7.
거부 목록 · 상태 디렉터리의 저장 자리」)입니다. auth 가 회수한 토큰은
resolve 가 `REVOKED` 로 거부하므로 목록이 비어도 다시 붙지 않습니다. 회수를
확인하는 방법(auth 쪽 조회)은 이 런북에 없습니다 — 확인하지 못하면 (a) 를
고릅니다.

두 갈래 공통 — 주소와 이름을 확인하고 뗍니다(T2).

```shell
param_addr='module.media_host.aws_ssm_parameter.denylist'
param_name=/pokeclip/prod/media/ingest-auth-deny-tokens
terraform -chdir="${media}" state pull \
  | jq -r '.resources[] | select(.mode == "managed")
      | select(.module == "module.media_host")
      | select(.type == "aws_ssm_parameter") | .instances[].attributes.name'
# ${param_name} 한 줄이어야 함(상태 전문은 화면 · 파일에 내지 않음 ·
# managed 만 — AMI 를 읽는 data 자원은 같은 type 이라 거름)
terraform -chdir="${media}" state rm -dry-run "${param_addr}"
terraform -chdir="${media}" state rm "${param_addr}"
terraform -chdir="${media}" state list | grep -c 'aws_ssm_parameter.denylist'   # 0
aws ssm get-parameter --region "${region}" --name "${param_name}" \
  --query Parameter.Name --output text   # 이름이 나옴(AWS 에는 그대로)
```

상태 버킷은 버전 관리가 켜져 있어(`modules/state_bucket`) 떼기 전 상태가
이전 버전으로 남습니다.

*   **(a) 의 다시 apply:** `prod/media` 를 다시 apply 하기 전에 import 합니다.
    `ignore_changes = [value]` 라 운영 값은 덮이지 않습니다.

    ```shell
    terraform -chdir="${media}" import "${param_addr}" "${param_name}"
    ```

*   **(b) 의 삭제:** 2(media destroy)가 끝난 뒤에 합니다 — 철거가 중간에
    멈추면 import 로 되돌릴 수 있게. 「6. (b) 파라미터 삭제」 에 있습니다.

## 2. prod/media 내리기

```shell
umask 077
terraform -chdir="${media}" plan -destroy -input=false -out="${work}/media.plan"
plan_summary "${media}" "${work}/media.plan" | tee "${evidence}/20-media-plan.txt"
grep -vc '^delete ' "${evidence}/20-media-plan.txt"            # 0
grep -vc ' module\.media_host\.' "${evidence}/20-media-plan.txt" # 0
grep -c 'aws_ssm_parameter' "${evidence}/20-media-plan.txt"     # 0
```

요지에는 상자(`aws_instance.this["<키>"]`) · 대상 등록(ingest 하나 · http
셋) · Media SG 와 짝 규칙(RDS SG 의 인그레스 ← Media · LB SG 의 이그레스 →
Media 포함) · 인스턴스 역할 · 프로필 · 정책이 나옵니다. kty 가 요지를
보고(T3) 승인하면 적용합니다.

```shell
terraform -chdir="${media}" apply -input=false "${work}/media.plan"
terraform -chdir="${media}" state list | wc -l   # 0
```

## 3. prod/platform 내리기

```shell
umask 077
terraform -chdir="${platform}" plan -destroy -input=false -out="${work}/platform.plan"
plan_summary "${platform}" "${work}/platform.plan" | tee "${evidence}/30-platform-plan.txt"
grep -vc '^delete ' "${evidence}/30-platform-plan.txt"   # 0
```

요지에는 ALB 둘 · NLB · 대상 그룹 · 리스너 · 규칙 · WAF · ECS 클러스터 ·
서비스 셋 · Cloud Map 네임스페이스 · 서비스 SG · 규칙(RDS SG 의 인그레스 ←
auth · clip 포함 — RDS SG 자체는 런타임 1단 것이라 남음) · 실행 · 작업
역할 · 로그 그룹(`pokeclip-prod-{next,auth,clip}`)이 나옵니다. 공개 WAF
로그를 더한 코드면 WAF 로그 그룹(`aws-waf-logs-pokeclip-prod-public`)과
로깅 설정(`aws_wafv2_web_acl_logging_configuration`)도 나옵니다.

*   로그 그룹은 destroy 와 함께 지워집니다(보존 30일). 남길 로그가 있으면
    T4 전에 kty 가 정합니다 — 내보내기 절차는 이 런북에 없습니다.

kty 가 요지를 보고(T4) 승인하면 적용합니다.

```shell
terraform -chdir="${platform}" apply -input=false "${work}/platform.plan"
terraform -chdir="${platform}" state list | wc -l   # 0
```

## 4. registry 저장소 둘(필요할 때만)

M9 코드를 되돌릴 때만 합니다 — 되돌리면 `repository_keys` 기본값에서 두
키가 빠집니다. 되돌리지 않으면 저장소를 남깁니다(다음 registry apply 가
다시 만들기 때문).

두 갈래가 있고 **기본은 (r1)** 입니다. 둘 다 끝 상태가 같고(저장소 둘
없음 · registry 상태와 코드 일치), 둘 다 이미지 삭제가 먼저입니다 —
`force_delete` 가 꺼져 있어 이미지가 남은 저장소는 어느 갈래에서도
지워지지 않습니다. 한 번에 한 갈래만 씁니다.

| 갈래 | 순서 | 고를 때 |
|---|---|---|
| (r1) revert 뒤 apply — 기본 | 이미지 삭제 → platform · media 잔여 0(「5. 잔여 0」, 저장소 둘 줄 제외) → revert 머지 → registry plan · apply(두 줄 삭제) → 저장소 둘 잔여 확인 | 보통. `-target` 없이 머지된 코드 그대로 plan 합니다 |
| (r2) revert 전 `-target` | 이미지 삭제 → 두 주소만 `-target` destroy → 잔여 0(저장소 둘 줄 포함) → revert 머지 → registry plan 변경 없음 | revert 머지를 기다리지 않고 지금 지워야 할 때 · 철거를 한자리에서 끝내야 할 때 |

**이미지 삭제**(두 갈래 공통 · media destroy 뒤 · T5). 같은 이미지가 태그마다
한 줄씩 나오므로 다이제스트로 모아 셉니다.

```shell
umask 077
for repo in media-host-bundle media-loopback-sender; do
  aws ecr list-images --region "${region}" \
    --repository-name "pokeclip-prod/${repo}" \
    --query imageIds --output json \
    | jq '[.[] | {imageDigest}] | unique' > "${work}/${repo}-ids.json"
  echo "${repo} $(jq length "${work}/${repo}-ids.json")"
done | tee "${evidence}/40-images.txt"
```

수를 kty 가 본 뒤 지웁니다. `batch-delete-image` 는 한 번에 100개까지라
100개씩 나눠 지웁니다(0개면 건너뜀).

```shell
umask 077
for repo in media-host-bundle media-loopback-sender; do
  n="$(jq length "${work}/${repo}-ids.json")"
  start=0
  while [ "${start}" -lt "${n}" ]; do
    jq ".[${start}:$((start + 100))]" "${work}/${repo}-ids.json" \
      > "${work}/${repo}-batch.json"
    aws ecr batch-delete-image --region "${region}" \
      --repository-name "pokeclip-prod/${repo}" \
      --image-ids "file://${work}/${repo}-batch.json" \
      --query 'length(failures)' --output text   # 0
    start=$((start + 100))
  done
  aws ecr list-images --region "${region}" \
    --repository-name "pokeclip-prod/${repo}" \
    --query 'length(imageIds)'   # 0
done
```

**(r1)** 이미지를 지운 뒤 revert 하기 **전에** 「5. 잔여 0」 을 저장소 둘 줄만
빼고 끝냅니다 — revert 하면 platform · media 의 구성이 사라져, 그 뒤에
잔여가 나오면 destroy 로 고칠 수 없습니다. 잔여 0 과 보존 목록 판정이 끝난
뒤에 revert 를 머지하고, 머지된 체크아웃에서 plan 합니다. 요지는 두 줄이어야
합니다(그 밖의 줄이 있으면 적용하지 않음).

```shell
umask 077
terraform -chdir="${registry}" plan -input=false -out="${work}/registry.plan"
plan_summary "${registry}" "${work}/registry.plan" | tee "${evidence}/41-registry-plan.txt"
# delete aws_ecr_repository.this["media-host-bundle"]
# delete aws_ecr_repository.this["media-loopback-sender"]
terraform -chdir="${registry}" apply -input=false "${work}/registry.plan"
```

apply 뒤 저장소 둘 잔여(「5. 잔여 0」 끝의 ECR 조회)를 보고, 그다음에
작업 디렉터리를 정리합니다.

**(r2)** revert 전 체크아웃에서 두 주소만 겨눠 destroy 합니다. 요지는 같은
두 줄입니다. 그 뒤 「5. 잔여 0」 을 저장소 둘 줄까지 봅니다. revert 가 머지된
뒤 `terraform -chdir="${registry}" plan` 이 변경 없음이어야 합니다.

```shell
umask 077
terraform -chdir="${registry}" plan -destroy -input=false \
  -target='aws_ecr_repository.this["media-host-bundle"]' \
  -target='aws_ecr_repository.this["media-loopback-sender"]' \
  -out="${work}/registry.plan"
plan_summary "${registry}" "${work}/registry.plan" | tee "${evidence}/41-registry-plan.txt"
terraform -chdir="${registry}" apply -input=false "${work}/registry.plan"
```

## 5. 잔여 0

읽기만 합니다. 결과를 증거 디렉터리에 남깁니다. 리전이 있는 조회는 모두
`--region "${region}"` 입니다(IAM 은 전역).

```shell
umask 077
{
  for root in prod/media prod/platform; do
    echo "## tagged ${root}"
    aws resourcegroupstaggingapi get-resources --region "${region}" \
      --tag-filters "Key=TerraformRoot,Values=${root}" \
      --query 'ResourceTagMappingList[].ResourceARN' --output json \
      | jq -r '.[]'
  done
  echo "## instances (not terminated)"
  aws ec2 describe-instances --region "${region}" \
    --filters Name=tag:TerraformRoot,Values=prod/media \
      Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down \
    --query 'length(Reservations[].Instances[])'
  echo "## security groups · rules"
  aws ec2 describe-security-groups --region "${region}" \
    --filters Name=tag:TerraformRoot,Values=prod/media,prod/platform \
    --query 'length(SecurityGroups)'
  aws ec2 describe-security-group-rules --region "${region}" \
    --filters Name=tag:TerraformRoot,Values=prod/media,prod/platform \
    --query 'length(SecurityGroupRules)'
  echo "## load balancers · target groups"
  aws elbv2 describe-load-balancers --region "${region}" \
    --query "length(LoadBalancers[?starts_with(LoadBalancerName, 'pokeclip-prod-')])"
  aws elbv2 describe-target-groups --region "${region}" \
    --query "length(TargetGroups[?starts_with(TargetGroupName, 'pokeclip-prod-')])"
  echo "## ecs · cloud map · waf"
  aws ecs describe-clusters --region "${region}" --clusters pokeclip-prod \
    --query 'length(clusters[?status==`ACTIVE`])'
  aws servicediscovery list-namespaces --region "${region}" \
    --query "length(Namespaces[?Name=='pokeclip.internal'])"
  aws wafv2 list-web-acls --scope REGIONAL --region "${region}" \
    --query "length(WebACLs[?Name=='pokeclip-prod-regional'])"
  echo "## log groups (prefix)"
  aws logs describe-log-groups --region "${region}" \
    --log-group-name-prefix pokeclip-prod- \
    --query 'logGroups[].logGroupName' --output text | tr '\t' '\n'
  echo "## waf log groups (prefix)"
  aws logs describe-log-groups --region "${region}" \
    --log-group-name-prefix aws-waf-logs-pokeclip-prod- \
    --query 'logGroups[].logGroupName' --output text | tr '\t' '\n'
  echo "## iam roles (prefix)"
  aws iam list-roles --path-prefix /pokeclip/prod/ \
    --query "Roles[?starts_with(RoleName, 'pokeclip-prod-')].RoleName" \
    --output text | tr '\t' '\n'
  aws iam get-instance-profile --instance-profile-name pokeclip-prod-media 2>&1 \
    | grep -c NoSuchEntity
  echo "## denylist parameter"
  aws ssm get-parameter --region "${region}" --name "${param_name}" 2>&1 \
    | grep -c ParameterNotFound
} | tee "${evidence}/50-residue.txt"
```

| 줄 | 통과 값 |
|---|---|
| `tagged prod/platform` | 빈 목록 |
| `tagged prod/media` | 빈 목록 — 갈래 (a) 면 거부 목록 파라미터 ARN 한 줄만(아래 「보존 목록」) |
| `instances` · `security groups · rules` · `load balancers · target groups` · `ecs · cloud map · waf` | 모두 `0` |
| `log groups` | `pokeclip-prod-next` · `pokeclip-prod-auth` · `pokeclip-prod-clip` 이 없음 |
| `waf log groups` | `0`건 — 공개 WAF 로그(접두 `aws-waf-logs-pokeclip-prod-`)를 아직 더하지 않았으면 처음부터 0건이 정상 |
| `iam roles` | `pokeclip-prod-media` · `pokeclip-prod-{next,auth,clip}-{execution,task}` 가 없음 |
| 인스턴스 프로필 | `1`(NoSuchEntity) |
| `denylist parameter` | `1`(ParameterNotFound — 갈래 (b) 의 6 뒤) — 갈래 (a) 면 `0`(있음 · 보존 목록) |

*   이름 접두 조회(로그 그룹 · 역할 · LB · 대상 그룹)에 위 이름 밖의 것이
    나오면 다른 루트의 자원일 수 있습니다. 지우지 않고 kty 가 확인합니다.
*   태그 조회는 종료된 인스턴스 · 삭제 중인 LB 네트워크 인터페이스를 몇 분
    보일 수 있습니다. `instances` 줄이 0 이면 몇 분 뒤 다시 봅니다.
*   ECS 태스크 정의가 destroy 뒤 비활성(`INACTIVE`)으로 태그 조회에 남는
    꼴인지는 O7d 때 확인합니다. 남으면 `aws ecs describe-task-definition
    --region "${region}"` 의 `status` 가 `INACTIVE` 인지 봅니다.
*   registry 갈래를 했으면 저장소 둘이 없는지도 봅니다(`0`). (r2) 는 이
    잔여 0 과 함께 봅니다. (r1) 은 이 줄만 빼고 잔여 0 을 끝낸 뒤 revert
    머지 · registry apply 를 하고, 그 뒤에 이 줄을 봅니다(「4. registry
    저장소 둘」 의 (r1)).

    ```shell
    aws ecr describe-repositories --region "${region}" --query "length(repositories[?repositoryName=='pokeclip-prod/media-host-bundle' || repositoryName=='pokeclip-prod/media-loopback-sender'])"
    ```

잔여가 있으면 이름으로 하나씩 확인해 kty 에게 보고합니다. platform ·
media 의 잔여 0 과 보존 목록 판정을 끝낸 뒤에만 revert 를 머지합니다 —
registry 갈래 (r1) 도 같습니다.

**보존 목록** — 잔여 0 과 따로, 일부러 남기는 것입니다. 증거
디렉터리에 `50-kept.txt` 로 한 줄씩(무엇 · 갈래 · 이유) 적습니다.

| 남기는 것 | 언제 | 이유 |
|---|---|---|
| 거부 목록 파라미터 | 갈래 (a) | 다시 apply 할 때 import 해 운영 값을 이어 씀 |
| registry 저장소 둘 | M9 코드를 되돌리지 않을 때 | 다음 registry apply 가 다시 만듦 |
| `prod/media` · `prod/platform` 상태 객체(빈 상태) | 늘 | 다시 apply 할 때 그대로 씀(「정리」) |
| 증거 디렉터리 | 늘 | 결과 원문(「정리」) |

## 6. (b) 파라미터 삭제

갈래 (b) 만 합니다. 값을 증거 디렉터리에 보관한 뒤(저장소 · 볼트에 올리지
않음) 지웁니다(T6).

```shell
umask 077
aws ssm get-parameter --region "${region}" --name "${param_name}" \
  --query Parameter.Value --output text > "${evidence}/60-denylist-value.txt"
test -s "${evidence}/60-denylist-value.txt" && echo saved
aws ssm delete-parameter --region "${region}" --name "${param_name}"
aws ssm get-parameter --region "${region}" --name "${param_name}" 2>&1 \
  | grep -c ParameterNotFound   # 1
```

이 파일이 거부했던 토큰의 유일한 사본입니다. 다시 apply 할 가능성이 있으면
지우지 않습니다.

### (b) 뒤 다시 apply

파라미터가 없는 채 `prod/media` 를 apply 하면 목록이 머리 줄만으로 생기고,
같은 apply 가 상자를 띄웁니다(상자의 시작 설정이 파라미터 이름을 참조해
파라미터가 먼저 생김). 그래서 값을 apply 뒤에 덮어쓰면 응답기가 그 사이에
빈 목록으로 뜰 수 있습니다. **apply 전에** 보관 파일에서 파라미터를 만들고
import 해, 응답기가 처음부터 보관한 목록을 읽게 합니다. 값은 파일에서
읽어(`file://`) 명령문 · 셸 기록에 나오지 않습니다.

먼저 「시작 전」 의 변수 · 리전 블록과 1절의 `param_addr` · `param_name` 을
다시 두고, `evidence` 를 **철거 때의** 증거 디렉터리로 둡니다.

```shell
umask 077
kept="${evidence}/60-denylist-value.txt"
test -s "${kept}" && echo found
aws ssm get-parameter --region "${region}" --name "${param_name}" 2>&1 \
  | grep -c ParameterNotFound   # 1 — 0 이면 누가 이미 만들었음: 멈춤
aws ssm put-parameter --region "${region}" --name "${param_name}" \
  --type String --tier Standard --value "file://${kept}" \
  --query Version --output text   # 1
terraform -chdir="${media}" import "${param_addr}" "${param_name}"
```

그다음 평소대로 `prod/media` 를 plan · apply 합니다. `ignore_changes =
[value]` 라 apply 가 되살린 값을 머리 줄로 덮지 않습니다. 보관 파일이
없거나 비었으면 apply 하지 않고 kty 에게 보고합니다.

## 부분 실패

*   destroy apply 가 중간에 실패하면 다시 돌리기 전에 오류를 kty 에게
    보고합니다. 거부 목록 파라미터 밖의 자원을 `state rm` 으로 떼어 넘기지
    않습니다 — 떼면 그 자원이 관리 밖에 남습니다.
*   SG 삭제가 `DependencyViolation` 으로, Cloud Map 네임스페이스 삭제가
    사용 중으로 막히면 다른 루트(런타임 2단 · 일꾼 · chat)의 자원이 아직
    가리키고 있을 수 있습니다. 멈추고 「시작 전」 4 를 다시 봅니다.
*   다시 할 때는 같은 plan 요지 절차로 plan 을 새로 만듭니다(남은 자원만
    나옴).
*   멈춘 채 오늘 이어 하지 않으면 작업 디렉터리를 그 자리에서 지웁니다
    (「정리」 의 명령). plan 파일에는 계정 ID 가 든 ARN 이 있고, 다시 할 때는
    plan 을 새로 만들므로 남길 까닭이 없습니다. 증거 디렉터리는 남깁니다.

## 정리

상태 객체(`prod/media` · `prod/platform` 의 빈 상태)는 지우지 않습니다 —
다시 apply 할 때 그대로 씁니다. 작업 디렉터리는 잔여 0 을 확인한 뒤
지웁니다. 증거 디렉터리는 남깁니다.

```shell
rm -r -- "${work}" && unset work
```

*   결과 원문(`${evidence}`)에는 계정 ID · ARN · 거부 목록 값이 있어 저장소 ·
    볼트에 그대로 올리지 않습니다. 볼트로 옮기는 것은 kty 가 「저장해」 할
    때만이고, 가린 요약(날짜 · 지운 루트 · plan 요지 줄 수 · 잔여 판정
    줄)만 옮깁니다.

## See also

*   [prod/media](../prod/media/README.md) · [prod/platform](../prod/platform/README.md) ·
    [prod/registry](../prod/registry/README.md)
*   [Media 교체 런북](media-swap.md) — 「plan 범위」 · 「부분 실패 복구」
*   [단계 직전 점검 런북](stage-precheck.md) — 「증거」
*   계획 3절 「SCP 멈춤 규칙」 · guard R10(`policy/guard/prevent_destroy.rego`)
