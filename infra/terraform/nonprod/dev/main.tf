# dev 상자(EC2 · EIP · SG)를 무변경으로 import 하기 위한 최소 선언.
# 설정에 적지 않은 속성은 provider 가 실물 값을 그대로 둔다(Optional ·
# Computed). 운영 O1 의 첫 plan 이 「import N · 생성 0 · 수정 0 · 삭제 0」
# 이 아니면 apply 하지 않고 이 선언 · tfvars 를 실물에 맞춘다.

locals {
  # 키 = 포트. M12 에서 "8082" 를 뺀다.
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

  lifecycle {
    ignore_changes = [ami, user_data]
  }
}

resource "aws_eip" "dev" {
  domain   = "vpc"
  instance = aws_instance.dev.id
  tags     = var.eip_tags
}

resource "aws_security_group" "dev" {
  description = var.security_group_description
  tags        = var.security_group_tags
}

resource "aws_vpc_security_group_ingress_rule" "dev" {
  for_each = local.dev_ingress_rules

  security_group_id = aws_security_group.dev.id
  ip_protocol       = "tcp"
  from_port         = each.value
  to_port           = each.value
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "dev" {
  security_group_id = aws_security_group.dev.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
