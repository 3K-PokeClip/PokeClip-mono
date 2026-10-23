# SRT 수신 NLB 모듈(설계 3-3 media_ingest · 5-5 「로드밸런서 SG」)을 mock
# provider 로 단언한다 — 계획 M8 「c4 단언 목록」: 서브넷 1개 · 인터넷 대면 ·
# UDP 8890 대상 그룹(instance) · 헬스 TCP 별도 포트 · connection_termination ·
# 지연 30초 · NLB SG 인그레스 UDP 8890 출처 = ingest_allowed_cidrs(비면 실패).
# CIDR 은 문서용 범위(RFC 5737)의 가짜 값이다.

# 리스너는 ARN 꼴을 검사하므로 NLB · 대상 그룹 ARN 만 가짜 ARN 으로 고정한다.
mock_provider "aws" {
  mock_resource "aws_lb" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/net/example-nlb/0123456789abcdef"
    }
  }

  mock_resource "aws_lb_target_group" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-ingest/0123456789abcdef"
    }
  }
}

variables {
  name                 = "example"
  vpc_id               = "vpc-0example"
  subnet_id            = "subnet-0media"
  health_port          = 8081
  ingest_allowed_cidrs = ["192.0.2.10/32", "198.51.100.0/28"]
}

run "creates_internet_facing_nlb_in_single_subnet" {
  command = plan

  assert {
    condition     = aws_lb.this.load_balancer_type == "network" && aws_lb.this.internal == false
    error_message = "NLB 가 인터넷 대면 network 로드밸런서가 아니다."
  }

  assert {
    condition     = aws_lb.this.subnets == toset(["subnet-0media"])
    error_message = "NLB 서브넷이 입력 subnet_id 하나가 아니다."
  }

  assert {
    condition     = aws_lb.this.name == "example-nlb"
    error_message = "NLB 이름이 <name>-nlb 가 아니다."
  }
}

run "creates_udp_target_group_with_tcp_health_on_separate_port" {
  command = plan

  assert {
    condition = (
      aws_lb_target_group.srt.protocol == "UDP" &&
      aws_lb_target_group.srt.port == 8890 &&
      aws_lb_target_group.srt.target_type == "instance" &&
      aws_lb_target_group.srt.vpc_id == "vpc-0example"
    )
    error_message = "대상 그룹이 UDP 8890 · instance · 입력 VPC 가 아니다."
  }

  # 헬스는 UDP 로 볼 수 없어 TCP 로, 트래픽 포트가 아닌 Media 헬스 포트로 본다.
  assert {
    condition = (
      aws_lb_target_group.srt.health_check[0].protocol == "TCP" &&
      aws_lb_target_group.srt.health_check[0].port == "8081"
    )
    error_message = "대상 그룹 헬스가 TCP · health_port 가 아니다."
  }

  # 등록 해제 때 남은 UDP 흐름을 끊어 교체 뒤 송출이 새 대상으로 붙게 한다.
  assert {
    condition     = aws_lb_target_group.srt.connection_termination == true
    error_message = "connection_termination 이 true 가 아니다."
  }

  assert {
    condition     = aws_lb_target_group.srt.deregistration_delay == "30"
    error_message = "등록 해제 지연이 30초가 아니다."
  }

  assert {
    condition     = aws_lb_target_group.srt.name == "example-ingest"
    error_message = "대상 그룹 이름이 <name>-ingest 가 아니다."
  }
}

run "uses_srt_port_and_deregistration_delay_inputs" {
  command = plan

  variables {
    srt_port             = 9000
    deregistration_delay = 45
  }

  assert {
    condition     = aws_lb_target_group.srt.port == 9000 && aws_lb_listener.srt.port == 9000
    error_message = "대상 그룹 · 리스너 포트가 입력 srt_port 가 아니다."
  }

  assert {
    condition     = aws_lb_target_group.srt.deregistration_delay == "45"
    error_message = "등록 해제 지연이 입력 deregistration_delay 가 아니다."
  }

  assert {
    condition     = alltrue([for rule in aws_vpc_security_group_ingress_rule.srt : rule.from_port == 9000 && rule.to_port == 9000])
    error_message = "SG 인그레스 포트가 입력 srt_port 가 아니다."
  }
}

# id 는 계산 속성이다. apply 로 돌려 mock 이 자원마다 다른 id 를 만들게 한 뒤
# 연결이 맞는 자원을 가리키는지 본다.
run "wires_udp_listener_and_security_group" {
  command = apply

  assert {
    condition = (
      aws_lb_listener.srt.load_balancer_arn == aws_lb.this.arn &&
      aws_lb_listener.srt.protocol == "UDP" &&
      aws_lb_listener.srt.port == 8890
    )
    error_message = "리스너가 NLB 의 UDP 8890 이 아니다."
  }

  assert {
    condition = (
      aws_lb_listener.srt.default_action[0].type == "forward" &&
      aws_lb_listener.srt.default_action[0].target_group_arn == aws_lb_target_group.srt.arn
    )
    error_message = "리스너 기본 동작이 SRT 대상 그룹으로 forward 가 아니다."
  }

  assert {
    condition     = aws_lb.this.security_groups == toset([aws_security_group.nlb.id])
    error_message = "NLB 에 모듈의 SG 하나만 붙지 않았다."
  }

  assert {
    condition     = aws_security_group.nlb.vpc_id == "vpc-0example" && aws_security_group.nlb.name == "example-nlb"
    error_message = "NLB SG 가 입력 VPC 의 <name>-nlb 가 아니다."
  }
}

run "allows_srt_ingress_only_from_allowed_cidrs" {
  command = apply

  # 선언된 for_each 의 키 집합 = 출처 집합. 0.0.0.0/0 은 넣지 않았다.
  assert {
    condition     = toset(keys(aws_vpc_security_group_ingress_rule.srt)) == toset(var.ingest_allowed_cidrs)
    error_message = "SRT 인그레스 규칙 키 집합이 ingest_allowed_cidrs 와 다르다."
  }

  assert {
    condition = alltrue([
      for cidr, rule in aws_vpc_security_group_ingress_rule.srt :
      rule.cidr_ipv4 == cidr &&
      rule.ip_protocol == "udp" &&
      rule.from_port == 8890 && rule.to_port == 8890 &&
      rule.security_group_id == aws_security_group.nlb.id
    ])
    error_message = "SRT 인그레스 규칙이 NLB SG 의 UDP 8890 ← 키 CIDR 가 아니다."
  }
}

run "outputs_match_contract" {
  command = apply

  assert {
    condition = (
      output.nlb_dns_name == aws_lb.this.dns_name &&
      output.nlb_zone_id == aws_lb.this.zone_id &&
      output.nlb_sg_id == aws_security_group.nlb.id &&
      output.target_group_arn == aws_lb_target_group.srt.arn
    )
    error_message = "출력이 NLB · SG · 대상 그룹과 다르다."
  }
}

run "rejects_empty_ingest_allowed_cidrs" {
  command = plan

  variables {
    ingest_allowed_cidrs = []
  }

  expect_failures = [var.ingest_allowed_cidrs]
}

run "rejects_invalid_ingest_allowed_cidr" {
  command = plan

  variables {
    ingest_allowed_cidrs = ["192.0.2.10"]
  }

  expect_failures = [var.ingest_allowed_cidrs]
}

# 헬스는 트래픽과 다른 포트(TCP)여야 한다.
# /16 보다 넓은 출처(0.0.0.0/0 등)는 공개 승인(S1 게이트 · 응답기 — 설계 4-6)
# 뒤에만 받는다.
run "rejects_wide_cidr_without_public_approval" {
  command = plan

  variables {
    ingest_allowed_cidrs = ["0.0.0.0/0"]
  }

  expect_failures = [var.ingest_allowed_cidrs]
}

run "rejects_slash_8_without_public_approval" {
  command = plan

  variables {
    ingest_allowed_cidrs = ["192.0.2.10/32", "10.0.0.0/8"]
  }

  expect_failures = [var.ingest_allowed_cidrs]
}

run "accepts_wide_cidr_with_public_approval" {
  command = plan

  variables {
    ingest_allowed_cidrs   = ["0.0.0.0/0"]
    ingest_public_approved = true
  }

  assert {
    condition     = toset(keys(aws_vpc_security_group_ingress_rule.srt)) == toset(["0.0.0.0/0"])
    error_message = "공개 승인 뒤에도 0.0.0.0/0 인그레스가 만들어지지 않는다."
  }
}

run "accepts_slash_16_and_narrower_without_approval" {
  command = plan

  variables {
    ingest_allowed_cidrs = ["198.51.0.0/16", "192.0.2.0/24"]
  }

  assert {
    condition     = toset(keys(aws_vpc_security_group_ingress_rule.srt)) == toset(["198.51.0.0/16", "192.0.2.0/24"])
    error_message = "/16 · /24 출처가 승인 없이 받아지지 않는다."
  }
}

run "rejects_health_port_equal_to_srt_port" {
  command = plan

  variables {
    health_port = 8890
  }

  expect_failures = [var.health_port]
}

run "rejects_health_port_out_of_range" {
  command = plan

  variables {
    health_port = 70000
  }

  expect_failures = [var.health_port]
}
