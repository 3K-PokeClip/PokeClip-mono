# dev 상자(EC2 · EIP · SG)를 무변경으로 import 하기 위한 최소 선언.
# 설정에 적지 않은 속성은 provider 가 실물 값을 그대로 둔다(Optional ·
# Computed). 운영 O1 의 첫 plan 이 「import N · 생성 0 · 수정 0 · 삭제 0」
# 이 아니면 apply 하지 않고 이 선언 · tfvars 를 실물에 맞춘다.
# O1 전 읽기 조사로 확인할 가정은 terraform.tfvars.example 머리 목록에 있다.

locals {
  # 키 = 포트. M12 에서 "8082" 를 뺀다. 규칙별 현재 값은 var.ingress_rules.
  dev_ingress_rules = {
    "80"   = 80
    "443"  = 443
    "8082" = 8082
  }
}

resource "aws_instance" "dev" {
  ami           = var.instance_ami
  instance_type = var.instance_type
  tags          = var.instance_tags

  # hibernation 은 바뀌면 인스턴스를 새로 만든다(ForceNew). 둘 다 현재 값.
  hibernation       = var.instance_hibernation
  source_dest_check = var.instance_source_dest_check

  # 프로필은 선언만 한다 — 프로필 · 역할 자체는 import 하지 않는다.
  iam_instance_profile = var.instance_iam_instance_profile

  lifecycle {
    prevent_destroy = true
    ignore_changes  = [ami, user_data]
  }
}

resource "aws_eip" "dev" {
  domain   = "vpc"
  instance = aws_instance.dev.id
  tags     = var.eip_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_security_group" "dev" {
  description = var.security_group_description
  tags        = var.security_group_tags

  lifecycle {
    prevent_destroy = true
  }
}

# 규칙은 prevent_destroy 를 걸지 않는다: M12 가 8082 규칙을 지운다.
resource "aws_vpc_security_group_ingress_rule" "dev" {
  for_each = local.dev_ingress_rules

  security_group_id = aws_security_group.dev.id
  ip_protocol       = var.ingress_rules[each.key].ip_protocol
  from_port         = each.value
  to_port           = each.value
  cidr_ipv4         = var.ingress_rules[each.key].cidr_ipv4
  description       = var.ingress_rules[each.key].description
  tags              = var.ingress_rules[each.key].tags
}

resource "aws_vpc_security_group_egress_rule" "dev" {
  security_group_id = aws_security_group.dev.id
  ip_protocol       = var.egress_rule.ip_protocol
  cidr_ipv4         = var.egress_rule.cidr_ipv4
  description       = var.egress_rule.description
  tags              = var.egress_rule.tags
}
