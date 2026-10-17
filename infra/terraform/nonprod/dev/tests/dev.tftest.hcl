# dev 상자 import 구성의 선언을 mock provider 로 단언한다.
# 무변경(No changes)은 tftest 로 볼 수 없어 운영 O1 plan 이 판정한다.
# EC2 · EIP · SG 의 prevent_destroy 는 tftest 로 볼 수 없어 guard R11 이 맡는다.

mock_provider "aws" {}

# mock provider 는 import 를 못 하므로 import 대상마다 override 를 둔다.
# 값을 비워 두면 선언(설정)의 값이 plan 에 그대로 남는다.
override_resource {
  target          = aws_instance.dev
  override_during = plan
}

override_resource {
  target          = aws_security_group.dev
  override_during = plan
}

override_resource {
  target          = aws_vpc_security_group_ingress_rule.dev
  override_during = plan
}

override_resource {
  target          = aws_vpc_security_group_egress_rule.dev
  override_during = plan
}

override_resource {
  target          = aws_eip.dev
  override_during = plan
  values = {
    public_ip = "192.0.2.10"
  }
}

variables {
  instance_id                = "i-0123456789abcdef0"
  eip_allocation_id          = "eipalloc-0123456789abcdef0"
  security_group_id          = "sg-0123456789abcdef0"
  ingress_rule_id_80         = "sgr-00000000000000080"
  ingress_rule_id_443        = "sgr-00000000000000443"
  ingress_rule_id_8082       = "sgr-00000000000008082"
  egress_rule_id             = "sgr-000000000000000e0"
  instance_ami               = "ami-0123456789abcdef0"
  instance_type              = "t3.small"
  security_group_description = "example"
  instance_tags              = { Name = "example" }
  eip_tags                   = {}
  security_group_tags        = {}
  instance_hibernation       = true
  instance_source_dest_check = false

  instance_iam_instance_profile = "example-profile"

  # 규칙마다 다른 값을 넣어, 선언이 키별 현재 값을 그대로 쓰는지 본다.
  ingress_rules = {
    "80" = {
      ip_protocol = "tcp"
      cidr_ipv4   = "0.0.0.0/0"
      description = "example http"
      tags        = { Name = "example-80" }
    }
    "443" = {
      ip_protocol = "tcp"
      cidr_ipv4   = "0.0.0.0/0"
    }
    "8082" = {
      ip_protocol = "tcp"
      cidr_ipv4   = "198.51.100.0/24"
      description = "example admin"
    }
  }
  egress_rule = {
    ip_protocol = "-1"
    cidr_ipv4   = "0.0.0.0/0"
    description = "example egress"
    tags        = { Name = "example-egress" }
  }
}

run "declares_dev_box" {
  command = plan

  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.dev) == 3
    error_message = "dev 인그레스 규칙이 3개(80 · 443 · 8082)가 아니다."
  }

  assert {
    condition = toset([
      for rule in aws_vpc_security_group_ingress_rule.dev : rule.from_port
    ]) == toset([80, 443, 8082])
    error_message = "dev 인그레스 포트 집합이 {80, 443, 8082} 가 아니다."
  }

  assert {
    condition = alltrue([
      for key, rule in aws_vpc_security_group_ingress_rule.dev :
      rule.to_port == rule.from_port && tostring(rule.from_port) == key
    ])
    error_message = "dev 인그레스 규칙이 키와 같은 단일 포트가 아니다."
  }

  assert {
    condition = alltrue([
      for key, rule in aws_vpc_security_group_ingress_rule.dev :
      rule.ip_protocol == var.ingress_rules[key].ip_protocol &&
      rule.cidr_ipv4 == var.ingress_rules[key].cidr_ipv4
    ])
    error_message = "dev 인그레스 규칙의 protocol · CIDR 이 ingress_rules 의 현재 값과 다르다."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.dev["80"].description == "example http" &&
      aws_vpc_security_group_ingress_rule.dev["443"].description == null &&
      aws_vpc_security_group_ingress_rule.dev["8082"].description == "example admin"
    )
    error_message = "dev 인그레스 규칙의 description 이 키별 현재 값과 다르다."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.dev["80"].tags == tomap({ Name = "example-80" }) &&
      aws_vpc_security_group_ingress_rule.dev["443"].tags == null &&
      aws_vpc_security_group_ingress_rule.dev["8082"].tags == null
    )
    error_message = "dev 인그레스 규칙의 tags 가 키별 현재 값(빈 태그는 null)과 다르다."
  }

  assert {
    condition = (
      aws_vpc_security_group_egress_rule.dev.ip_protocol == "-1" &&
      aws_vpc_security_group_egress_rule.dev.cidr_ipv4 == "0.0.0.0/0" &&
      aws_vpc_security_group_egress_rule.dev.description == "example egress" &&
      aws_vpc_security_group_egress_rule.dev.tags == tomap({ Name = "example-egress" })
    )
    error_message = "dev 이그레스 규칙이 egress_rule 의 현재 값과 다르다."
  }

  assert {
    condition     = aws_instance.dev.hibernation == true
    error_message = "EC2 hibernation 이 instance_hibernation 현재 값과 다르다."
  }

  assert {
    condition     = aws_instance.dev.source_dest_check == false
    error_message = "EC2 source_dest_check 가 instance_source_dest_check 현재 값과 다르다."
  }

  assert {
    condition     = aws_instance.dev.iam_instance_profile == "example-profile"
    error_message = "EC2 iam_instance_profile 이 instance_iam_instance_profile 현재 값과 다르다."
  }

  assert {
    condition     = aws_eip.dev.domain == "vpc"
    error_message = "EIP domain 이 vpc 가 아니다."
  }

  assert {
    condition     = output.dev_eip == "192.0.2.10"
    error_message = "출력 dev_eip 가 EIP 공인 주소가 아니다."
  }
}

# 실제 규칙에 태그가 없으면 tags 는 null 이다. 빈 맵({})을 넘기면 O1 plan 이
# 「+ tags = {}」 수정을 낸다 — 빈 입력은 생략 · {} 어느 쪽이든 null 로 간다.
run "omits_empty_rule_tags" {
  command = plan

  variables {
    ingress_rules = {
      "80"   = { ip_protocol = "tcp", cidr_ipv4 = "0.0.0.0/0", tags = {} }
      "443"  = { ip_protocol = "tcp", cidr_ipv4 = "0.0.0.0/0" }
      "8082" = { ip_protocol = "tcp", cidr_ipv4 = "0.0.0.0/0", tags = {} }
    }
    egress_rule = {
      ip_protocol = "-1"
      cidr_ipv4   = "0.0.0.0/0"
      tags        = {}
    }
  }

  assert {
    condition = alltrue([
      for rule in aws_vpc_security_group_ingress_rule.dev : rule.tags == null
    ])
    error_message = "빈 입력인 인그레스 규칙 tags 가 null 이 아니다."
  }

  assert {
    condition     = aws_vpc_security_group_egress_rule.dev.tags == null
    error_message = "빈 입력인 이그레스 규칙 tags 가 null 이 아니다."
  }
}

run "rejects_malformed_instance_id" {
  command = plan

  variables {
    instance_id = "<INSTANCE_ID_DEV>"
  }

  expect_failures = [var.instance_id]
}

run "rejects_empty_instance_profile" {
  command = plan

  variables {
    instance_iam_instance_profile = ""
  }

  expect_failures = [var.instance_iam_instance_profile]
}

run "rejects_malformed_instance_profile" {
  command = plan

  variables {
    instance_iam_instance_profile = "<INSTANCE_PROFILE_NAME_DEV>"
  }

  expect_failures = [var.instance_iam_instance_profile]
}

run "rejects_placeholder_rule_cidr" {
  command = plan

  variables {
    ingress_rules = {
      "80"   = { ip_protocol = "tcp", cidr_ipv4 = "<CIDR_DEV_80>" }
      "443"  = { ip_protocol = "tcp", cidr_ipv4 = "0.0.0.0/0" }
      "8082" = { ip_protocol = "tcp", cidr_ipv4 = "0.0.0.0/0" }
    }
  }

  expect_failures = [var.ingress_rules]
}

run "rejects_rule_keys_other_than_declared_ports" {
  command = plan

  variables {
    ingress_rules = {
      "80"  = { ip_protocol = "tcp", cidr_ipv4 = "0.0.0.0/0" }
      "443" = { ip_protocol = "tcp", cidr_ipv4 = "0.0.0.0/0" }
      "22"  = { ip_protocol = "tcp", cidr_ipv4 = "0.0.0.0/0" }
    }
  }

  expect_failures = [var.ingress_rules]
}
