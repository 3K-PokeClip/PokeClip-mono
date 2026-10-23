# ALB 교차 영역 실측 런북

공개 · 내부 ALB 가 AZ 두 곳에 노드를 두고 대상은 한 AZ 에 한 대만 있을 때,
**대상이 없는 AZ 의 노드로 들어온 요청도 성공하는지** 재는 절차입니다(설계
5-1 의 (f) · 계획 M8 c9). prod 는 next · auth · clip 태스크와 Media 상자가
각각 한 대라 이 전제에 기댑니다.

AWS 문서가 엇갈립니다 — ALB 대상 등록 문서는 「AZ 마다 대상을 둬야 한다」,
ELB 가이드는 「그게 가장 효과적」 이라고 씁니다. ALB 는 교차 영역 분산이 늘
켜져 있어 대상 한 대로도 동작할 것으로 보지만 추정입니다(설계 게이트 14_re
E9). 그래서 O7d 의 platform apply 전에 실물로 잽니다.

용어:

*   **노드**: ALB 가 서브넷(AZ)마다 두는 실제 수신 지점입니다. DNS 이름을
    풀면 AZ 수만큼 IP 가 나옵니다.
*   **교차 영역 분산**: 한 AZ 의 노드가 다른 AZ 의 대상에도 요청을 보내는
    것입니다. ALB 는 로드밸런서 수준에서 늘 켜져 있고, 대상 그룹 속성
    `load_balancing.cross_zone.enabled` 로만 끌 수 있습니다.

## 언제 · 누가

| 항목 | 내용 |
|---|---|
| 단계 | O7d 의 첫 일 — (f) 실측 → 런타임 1단 출력 확인 → platform apply(계획 M8 O7d) |
| 선행 | [단계 직전 점검](stage-precheck.md) `O7d` 묶음 통과 · `prod/network` 적용(O7a) |
| 실행자 | kty 로컬 터미널(kty IAM 사용자 프로필). 출력에 계정 ID · ARN 이 찍히므로 세션 · 문서 · 채팅에 붙여 넣지 않습니다 |
| 승인 | 실험 자원 생성(apply) · 삭제(destroy) · SSM 명령마다 실행 직전에 kty |
| 비용 | ALB 두 개 · t4g.nano 한 대를 한 시간 안쪽으로 — 1달러 미만(추정) |

## 실험 자원

| 자원 | 이름 | 역할 |
|---|---|---|
| EC2(AL2023 arm64 · t4g.nano) | `pokeclip-bench-crosszone-target` | 대상 한 대 — `target_subnet_id`(프라이빗, AZ 하나)에서 HTTP 8080 |
| ALB 두 개 | `pcbench-xz-public`(인터넷 대면 · 퍼블릭 서브넷 둘) · `pcbench-xz-internal`(내부 · 프라이빗 서브넷 둘) | 리스너 HTTP 80 |
| 대상 그룹 두 개 | `pcbench-xz-public`(ip 대상 — Fargate 와 같음) · `pcbench-xz-internal`(instance 대상 — Media 와 같음) | 대상 = 위 EC2 |
| SG 두 개 · 규칙 | `pokeclip-bench-crosszone-{alb,target}` | ALB 인그레스 80 ← kty IP · VPC CIDR |
| IAM 역할 · 인스턴스 프로필 | `pokeclip-bench-crosszone-target`(경로 `/pokeclip/bench/`) | SSM 으로 내부 ALB 를 부르려고 |

*   모든 자원에 태그 `com.pokeclip.bench = alb-crosszone` 를 붙입니다(provider
    `default_tags`). 정리 명령은 이 태그만 겨눕니다.
*   **전용 상태**: 저장소 밖 작업 디렉터리의 로컬 상태입니다. prod 상태 버킷
    · 저장소 루트를 쓰지 않습니다.
*   HTTP 80 · 8080 으로 잽니다. 교차 영역 분산은 리스너 프로토콜과 무관해
    인증서가 필요 없습니다.
*   IAM 역할 경로는 `/pokeclip/bench/` 입니다(prod 경로 아님). 실측이 끝나면
    지우므로, 다음 2-3 판정 전에 남아 있지 않은지 아래 「잔여 0」 에서
    확인합니다.

## 1. 준비

작업 디렉터리를 저장소 밖에 0700 으로 만듭니다. 결과 원문도 같은 규칙의
증거 루트(`~/.pokeclip-precheck/`) 아래에 둡니다.

```shell
umask 077
bench_dir="${HOME}/.pokeclip-bench/alb-crosszone"
evidence_dir="${HOME}/.pokeclip-precheck/bench-alb-crosszone-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "${bench_dir}" "${evidence_dir}"
chmod 700 "${HOME}/.pokeclip-bench" "${bench_dir}" "${evidence_dir}"
```

시작 전 상태(태그 자원 0)를 남깁니다.

```shell
aws resourcegroupstaggingapi get-resources --region ap-northeast-2 \
  --tag-filters Key=com.pokeclip.bench,Values=alb-crosszone \
  --query 'ResourceTagMappingList[].ResourceARN' --output json \
  > "${evidence_dir}/00-before.json"
jq length "${evidence_dir}/00-before.json"   # 0 이어야 시작한다
```

`prod/network` 출력으로 tfvars 를 만듭니다. `target_subnet_id` 는 Media AZ
의 프라이빗 서브넷입니다. `client_cidr` 는 kty 의 현재 공인 IP `/32` 입니다.

```shell
net_json="$(terraform -chdir=infra/terraform/prod/network output -json)"
jq -n --argjson n "${net_json}" --arg client "<KTY_PUBLIC_IP>/32" '{
  vpc_id: $n.vpc_id.value,
  public_subnet_ids: $n.public_subnet_ids.value,
  private_subnet_ids: $n.private_subnet_ids.value,
  target_subnet_id: $n.media_private_subnet_id.value,
  client_cidr: $client
}' > "${bench_dir}/terraform.tfvars.json"
```

아래 구성을 `${bench_dir}/main.tf` 로 저장합니다.

```hcl
terraform {
  required_version = ">= 1.11.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.19.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-2"
  default_tags {
    tags = { "com.pokeclip.bench" = "alb-crosszone" }
  }
}

variable "vpc_id" { type = string }
variable "public_subnet_ids" { type = list(string) }
variable "private_subnet_ids" { type = list(string) }
variable "target_subnet_id" { type = string }
variable "client_cidr" { type = string }

locals {
  name = "pokeclip-bench-crosszone"
  port = 8080
}

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

data "aws_vpc" "this" {
  id = var.vpc_id
}

resource "aws_iam_role" "target" {
  name = "${local.name}-target"
  path = "/pokeclip/bench/"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.target.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "target" {
  name = "${local.name}-target"
  path = "/pokeclip/bench/"
  role = aws_iam_role.target.name
}

resource "aws_security_group" "alb" {
  name   = "${local.name}-alb"
  vpc_id = var.vpc_id
}

resource "aws_security_group" "target" {
  name   = "${local.name}-target"
  vpc_id = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_client" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = var.client_cidr
}

# 내부 ALB 는 VPC 안(대상 상자 자신)에서 부른다.
resource "aws_vpc_security_group_ingress_rule" "alb_from_vpc" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = data.aws_vpc.this.cidr_block
}

resource "aws_vpc_security_group_egress_rule" "alb_to_target" {
  security_group_id            = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = local.port
  to_port                      = local.port
  referenced_security_group_id = aws_security_group.target.id
}

resource "aws_vpc_security_group_ingress_rule" "target_from_alb" {
  security_group_id            = aws_security_group.target.id
  ip_protocol                  = "tcp"
  from_port                    = local.port
  to_port                      = local.port
  referenced_security_group_id = aws_security_group.alb.id
}

# SSM · 패키지 · 내부 ALB 호출(NAT · VPC 안).
resource "aws_vpc_security_group_egress_rule" "target_out" {
  security_group_id = aws_security_group.target.id
  ip_protocol       = "tcp"
  from_port         = 0
  to_port           = 65535
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_instance" "target" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.insecure_value
  instance_type          = "t4g.nano"
  subnet_id              = var.target_subnet_id
  vpc_security_group_ids = [aws_security_group.target.id]
  iam_instance_profile   = aws_iam_instance_profile.target.name
  user_data              = "#!/bin/bash\ncd /tmp && nohup python3 -m http.server ${local.port} >/dev/null 2>&1 &\n"

  metadata_options {
    http_tokens = "required"
  }

  tags = { Name = "${local.name}-target" }
}

# 공개 = Fargate 처럼 ip 대상, 내부 = Media 처럼 instance 대상.
resource "aws_lb" "this" {
  for_each = { public = false, internal = true }

  name               = "pcbench-xz-${each.key}"
  load_balancer_type = "application"
  internal           = each.value
  subnets            = each.value ? var.private_subnet_ids : var.public_subnet_ids
  security_groups    = [aws_security_group.alb.id]
}

resource "aws_lb_target_group" "this" {
  for_each = { public = "ip", internal = "instance" }

  name        = "pcbench-xz-${each.key}"
  vpc_id      = var.vpc_id
  port        = local.port
  protocol    = "HTTP"
  target_type = each.value

  health_check {
    path = "/"
  }
}

resource "aws_lb_target_group_attachment" "this" {
  for_each = aws_lb_target_group.this

  target_group_arn = each.value.arn
  target_id        = each.key == "public" ? aws_instance.target.private_ip : aws_instance.target.id
  port             = local.port
}

resource "aws_lb_listener" "this" {
  for_each = aws_lb.this

  load_balancer_arn = each.value.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.key].arn
  }
}

output "alb_dns_names" {
  value = { for key, lb in aws_lb.this : key => lb.dns_name }
}

output "target" {
  value = { instance_id = aws_instance.target.id, az = aws_instance.target.availability_zone }
}
```

## 2. 생성(kty 승인)

```shell
terraform -chdir="${bench_dir}" init -input=false
terraform -chdir="${bench_dir}" plan -input=false -out=bench.tfplan
terraform -chdir="${bench_dir}" apply -input=false bench.tfplan
terraform -chdir="${bench_dir}" output -json > "${evidence_dir}/10-outputs.json"
```

plan 에 태그 `com.pokeclip.bench` 가 없는 자원이 있거나, 예상 밖 자원(위
표 밖)이 있으면 apply 하지 않습니다. 생성 중 `AccessDenied` 가 나면 그 자리에서
멈추고 계획 3절 「SCP 멈춤 규칙」 을 따른 뒤 「4. 삭제」 로 갑니다.

두 대상 그룹이 healthy 가 될 때까지 기다리고(보통 2–3분), 대상의 AZ 와 교차
영역 속성을 남깁니다.

```shell
for tg in pcbench-xz-public pcbench-xz-internal; do
  tg_arn="$(aws elbv2 describe-target-groups --names "${tg}" \
    --query 'TargetGroups[0].TargetGroupArn' --output text)"
  aws elbv2 describe-target-health --target-group-arn "${tg_arn}" \
    > "${evidence_dir}/20-health-${tg}.json"
  aws elbv2 describe-target-group-attributes --target-group-arn "${tg_arn}" \
    > "${evidence_dir}/20-attrs-${tg}.json"
done
jq -r '.TargetHealthDescriptions[].TargetHealth.State' \
  "${evidence_dir}"/20-health-*.json            # 둘 다 healthy
jq -r '.Attributes[] | select(.Key=="load_balancing.cross_zone.enabled") | .Value' \
  "${evidence_dir}"/20-attrs-*.json             # use_load_balancer_configuration(기본)
```

## 3. 재기

노드마다 따로 부릅니다. ALB DNS 를 풀어 나온 IP 하나하나에 `--resolve` 로
20번씩 요청하고 상태 코드를 셉니다. 노드 IP 의 AZ 는 그 IP 를 가진 ALB 네트워크
인터페이스의 AZ 로 확인합니다.

**공개 ALB**(kty 로컬에서):

```shell
public_dns="$(jq -r '.alb_dns_names.value.public' "${evidence_dir}/10-outputs.json")"
for ip in $(dig +short "${public_dns}"); do
  aws ec2 describe-network-interfaces \
    --filters "Name=association.public-ip,Values=${ip}" \
    --query 'NetworkInterfaces[0].AvailabilityZone' --output text
  for _ in $(seq 20); do
    curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 \
      --resolve "${public_dns}:80:${ip}" "http://${public_dns}/"
  done | sort | uniq -c
done | tee "${evidence_dir}/30-public.txt"
```

**내부 ALB**(대상 상자 안에서 — SSM 명령, kty 승인). 상자 스크립트는 파일로
만들어 `jq` 로 넘깁니다(따옴표가 겹치지 않게). AL2023 기본 이미지에는 `dig`
가 없어 `getent` 로 풉니다.

```shell
internal_dns="$(jq -r '.alb_dns_names.value.internal' "${evidence_dir}/10-outputs.json")"
instance_id="$(jq -r '.target.value.instance_id' "${evidence_dir}/10-outputs.json")"
cat > "${bench_dir}/probe.sh" <<'SH'
for ip in $(getent ahostsv4 "$1" | awk '{print $1}' | sort -u); do
  echo "${ip}"
  for _ in $(seq 20); do
    curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 \
      --resolve "$1:80:${ip}" "http://$1/"
  done | sort | uniq -c
done
SH
jq -n --rawfile script "${bench_dir}/probe.sh" --arg dns "${internal_dns}" \
  '{commands: [("set -- " + $dns), $script]}' > "${bench_dir}/params.json"
command_id="$(aws ssm send-command --instance-ids "${instance_id}" \
  --document-name AWS-RunShellScript \
  --parameters "file://${bench_dir}/params.json" \
  --query 'Command.CommandId' --output text)"
sleep 15
aws ssm get-command-invocation --command-id "${command_id}" \
  --instance-id "${instance_id}" --query StandardOutputContent --output text \
  | tee "${evidence_dir}/30-internal.txt"
for ip in $(grep -E '^[0-9.]+$' "${evidence_dir}/30-internal.txt"); do
  aws ec2 describe-network-interfaces \
    --filters "Name=addresses.private-ip-address,Values=${ip}" \
    --query 'NetworkInterfaces[0].AvailabilityZone' --output text
done | tee -a "${evidence_dir}/30-internal.txt"
```

`AWS-RunShellScript` 는 `commands` 를 한 스크립트로 이어 돌립니다 — 첫 줄
`set -- <DNS>` 가 `$1` 을 정합니다. 상태가 `InProgress` 면 몇 초 뒤 다시
읽습니다.

**판정**:

| 결과 | 뜻 | 다음 |
|---|---|---|
| 두 ALB 모두 노드 IP 둘 다 `20 200` | 대상 없는 AZ 의 노드도 대상에 닿는다 — 전제 성립 | 「4. 삭제」 뒤 O7d 계속 |
| 어느 노드든 200 이 아닌 응답 · 시간 초과가 하나라도 | 전제 불성립 | 「4. 삭제」 뒤 **platform apply 를 시작하지 않고** kty 에게 보고(대안 — 서비스 · Media 를 AZ 마다 한 대씩 · 대상 그룹 교차 영역 설정 — 은 kty 결정) |
| 노드 IP 가 하나만 나옴 | 서브넷이 한 AZ 뿐 — 측정 무효 | 「4. 삭제」 뒤 network 출력 확인 |

## 4. 삭제(kty 승인)와 잔여 0

판정과 상관없이 실측이 끝나면 바로 지웁니다.

```shell
terraform -chdir="${bench_dir}" destroy -input=false
```

잔여 0 을 확인하고 남깁니다. ALB 네트워크 인터페이스는 삭제 뒤 몇 분 남을 수
있어 0 이 될 때까지 다시 봅니다.

```shell
{
  echo "## tagged"
  aws resourcegroupstaggingapi get-resources --region ap-northeast-2 \
    --tag-filters Key=com.pokeclip.bench,Values=alb-crosszone \
    --query 'length(ResourceTagMappingList)'
  echo "## load balancers"
  aws elbv2 describe-load-balancers --names pcbench-xz-public pcbench-xz-internal 2>&1 \
    | grep -c LoadBalancerNotFound
  echo "## instances (not terminated)"
  aws ec2 describe-instances \
    --filters Name=tag:com.pokeclip.bench,Values=alb-crosszone \
      Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down \
    --query 'length(Reservations[].Instances[])'
  echo "## security groups"
  aws ec2 describe-security-groups \
    --filters Name=tag:com.pokeclip.bench,Values=alb-crosszone \
    --query 'length(SecurityGroups)'
  echo "## iam"
  aws iam get-role --role-name pokeclip-bench-crosszone-target 2>&1 | grep -c NoSuchEntity
  aws iam get-instance-profile --instance-profile-name pokeclip-bench-crosszone-target 2>&1 \
    | grep -c NoSuchEntity
} | tee "${evidence_dir}/40-residue.txt"
```

| 줄 | 통과 값 |
|---|---|
| `tagged` · `instances` · `security groups` | `0`(태그 API 는 종료된 인스턴스를 잠시 보일 수 있다 — 그때는 `instances` 줄이 0 이면 통과) |
| `load balancers` | `1`(LoadBalancerNotFound) |
| `iam` 두 줄 | `1` · `1`(NoSuchEntity) |

잔여가 있으면 이름으로 하나씩 지우고 다시 확인합니다. 0 을 확인한 뒤에만
작업 디렉터리를 지웁니다(상태 · tfvars 에 계정에 묶인 값이 있습니다).

```shell
rm -r "${bench_dir}"
```

## 5. 결과 보관

*   원문(`${evidence_dir}` — 출력 · 판정 · 잔여)은 kty 로컬에 둡니다. 계정
    ID · ARN · 공인 IP 가 들어 있어 저장소 · 볼트에 그대로 올리지 않습니다.
*   볼트에는 가린 요약을 둡니다 — 날짜(UTC) · ALB 별 노드 AZ 둘 · 노드마다
    응답 코드 수 · 대상 AZ · 교차 영역 속성 값 · 판정 · 잔여 0 줄. 계정 ID ·
    ARN · IP · 인스턴스 id 는 지웁니다(계획 3절 「증거 경로」).
*   판정 줄은 설계 13절 미확인 목록의 (f) 를 닫는 근거로 씁니다.
