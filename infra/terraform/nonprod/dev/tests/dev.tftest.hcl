# dev 상자 import 구성의 선언을 mock provider 로 단언한다.
# 무변경(No changes)은 tftest 로 볼 수 없어 운영 O1 plan 이 판정한다.

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
      rule.to_port == rule.from_port && rule.ip_protocol == "tcp" &&
      rule.cidr_ipv4 == "0.0.0.0/0" && tostring(rule.from_port) == key
    ])
    error_message = "dev 인그레스 규칙이 키와 같은 단일 tcp 포트 · 0.0.0.0/0 이 아니다."
  }

  assert {
    condition     = aws_vpc_security_group_egress_rule.dev.ip_protocol == "-1"
    error_message = "dev 이그레스가 별도 규칙(전체 허용)으로 선언되지 않았다."
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

run "rejects_malformed_instance_id" {
  command = plan

  variables {
    instance_id = "<INSTANCE_ID_DEV>"
  }

  expect_failures = [var.instance_id]
}
