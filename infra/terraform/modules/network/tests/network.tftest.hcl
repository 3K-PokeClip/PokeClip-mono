# prod 네트워크 모듈(설계 3-1 의 ④ · 3-3)을 mock provider 로 단언한다 — AZ 2 ·
# 퍼블릭 · 프라이빗 서브넷 AZ 마다 하나 · NAT 1개(키 = nat_az) · S3 Gateway EP 가
# 프라이빗 라우트 테이블에만 붙음 · media_az / nat_az 는 azs 안 · 출력 이름.
# CIDR 은 문서용 범위(RFC 5737)의 가짜 값이다.

mock_provider "aws" {}

# S3 Gateway EP 의 서비스 이름은 provider 리전에서 나온다.
override_data {
  target = data.aws_region.current
  values = {
    region = "ap-northeast-2"
  }
}

variables {
  name                       = "example"
  cidr                       = "192.0.2.0/24"
  azs                        = ["ap-northeast-2a", "ap-northeast-2c"]
  media_az                   = "ap-northeast-2a"
  nat_az                     = "ap-northeast-2a"
  enable_s3_gateway_endpoint = true
}

run "creates_vpc_with_dns" {
  command = plan

  assert {
    condition     = aws_vpc.this.cidr_block == "192.0.2.0/24"
    error_message = "VPC CIDR 이 입력 cidr 이 아니다."
  }

  # Cloud Map 사설 존 · 인터페이스 엔드포인트 이름 풀이가 이 둘에 기댄다.
  assert {
    condition     = aws_vpc.this.enable_dns_support && aws_vpc.this.enable_dns_hostnames
    error_message = "VPC 의 DNS 지원 · DNS 호스트 이름이 켜져 있지 않다."
  }

  assert {
    condition     = aws_vpc.this.tags["Name"] == "example-vpc"
    error_message = "VPC Name 태그가 <name>-vpc 가 아니다."
  }
}

run "creates_public_and_private_subnet_per_az" {
  command = plan

  assert {
    condition     = toset(keys(aws_subnet.public)) == toset(var.azs) && toset(keys(aws_subnet.private)) == toset(var.azs)
    error_message = "퍼블릭 · 프라이빗 서브넷 키 집합이 azs(2개)와 다르다."
  }

  assert {
    condition = alltrue([
      for az in var.azs :
      aws_subnet.public[az].availability_zone == az && aws_subnet.private[az].availability_zone == az
    ])
    error_message = "서브넷이 키와 다른 AZ 에 놓였다."
  }

  # VPC 를 네 등분해 앞 둘은 퍼블릭, 뒤 둘은 프라이빗에 azs 순서로 준다.
  assert {
    condition = (
      aws_subnet.public["ap-northeast-2a"].cidr_block == "192.0.2.0/26" &&
      aws_subnet.public["ap-northeast-2c"].cidr_block == "192.0.2.64/26" &&
      aws_subnet.private["ap-northeast-2a"].cidr_block == "192.0.2.128/26" &&
      aws_subnet.private["ap-northeast-2c"].cidr_block == "192.0.2.192/26"
    )
    error_message = "서브넷 CIDR 이 VPC 네 등분(퍼블릭 0 · 1, 프라이빗 2 · 3)이 아니다."
  }
}

run "creates_single_nat_keyed_by_nat_az" {
  command = plan

  assert {
    condition     = keys(aws_nat_gateway.this) == ["ap-northeast-2a"] && keys(aws_eip.nat) == ["ap-northeast-2a"]
    error_message = "NAT · EIP 가 nat_az 키 하나가 아니다."
  }

  assert {
    condition     = aws_eip.nat["ap-northeast-2a"].domain == "vpc"
    error_message = "NAT EIP 가 VPC 용(domain = vpc)이 아니다."
  }
}

# id 는 계산 속성이다. apply 로 돌려 mock 이 자원마다 다른 id 를 만들게 한 뒤
# 연결이 맞는 자원을 가리키는지 본다.
run "wires_routes_through_igw_and_single_nat" {
  command = apply

  variables {
    nat_az = "ap-northeast-2c"
  }

  assert {
    condition     = aws_nat_gateway.this["ap-northeast-2c"].subnet_id == aws_subnet.public["ap-northeast-2c"].id
    error_message = "NAT 가 nat_az 의 퍼블릭 서브넷에 있지 않다."
  }

  assert {
    condition     = aws_route.public_internet.route_table_id == aws_route_table.public.id && aws_route.public_internet.gateway_id == aws_internet_gateway.this.id && aws_route.public_internet.destination_cidr_block == "0.0.0.0/0"
    error_message = "퍼블릭 기본 경로가 인터넷 게이트웨이로 가지 않는다."
  }

  assert {
    condition = alltrue([
      for az in var.azs :
      aws_route_table_association.public[az].subnet_id == aws_subnet.public[az].id &&
      aws_route_table_association.public[az].route_table_id == aws_route_table.public.id
    ])
    error_message = "퍼블릭 서브넷이 퍼블릭 라우트 테이블에 연결되지 않았다."
  }

  # 프라이빗 라우트 테이블은 AZ 마다 하나 — 모두 하나뿐인 NAT 로 나간다.
  assert {
    condition = toset(keys(aws_route_table.private)) == toset(var.azs) && alltrue([
      for az in var.azs :
      aws_route.private_nat[az].route_table_id == aws_route_table.private[az].id &&
      aws_route.private_nat[az].nat_gateway_id == aws_nat_gateway.this["ap-northeast-2c"].id &&
      aws_route.private_nat[az].destination_cidr_block == "0.0.0.0/0"
    ])
    error_message = "프라이빗 기본 경로가 AZ 마다 nat_az 의 NAT 로 가지 않는다."
  }

  assert {
    condition = alltrue([
      for az in var.azs :
      aws_route_table_association.private[az].subnet_id == aws_subnet.private[az].id &&
      aws_route_table_association.private[az].route_table_id == aws_route_table.private[az].id
    ])
    error_message = "프라이빗 서브넷이 같은 AZ 의 프라이빗 라우트 테이블에 연결되지 않았다."
  }
}

run "attaches_s3_gateway_endpoint_to_private_route_tables" {
  command = apply

  assert {
    condition     = aws_vpc_endpoint.s3[0].service_name == "com.amazonaws.ap-northeast-2.s3" && aws_vpc_endpoint.s3[0].vpc_endpoint_type == "Gateway"
    error_message = "S3 엔드포인트가 provider 리전의 Gateway 꼴이 아니다."
  }

  assert {
    condition     = toset(keys(aws_vpc_endpoint_route_table_association.s3)) == toset(keys(aws_route_table.private))
    error_message = "S3 엔드포인트 연결 라우트 테이블 키 집합이 프라이빗과 다르다."
  }

  assert {
    condition = alltrue([
      for az, association in aws_vpc_endpoint_route_table_association.s3 :
      association.route_table_id == aws_route_table.private[az].id &&
      association.vpc_endpoint_id == aws_vpc_endpoint.s3[0].id
    ])
    error_message = "S3 엔드포인트 연결이 같은 키의 프라이빗 라우트 테이블을 가리키지 않는다."
  }

  assert {
    condition = alltrue([
      for association in aws_vpc_endpoint_route_table_association.s3 :
      association.route_table_id != aws_route_table.public.id
    ])
    error_message = "S3 엔드포인트가 퍼블릭 라우트 테이블에 붙었다."
  }

  assert {
    condition     = output.s3_prefix_list_id == aws_vpc_endpoint.s3[0].prefix_list_id
    error_message = "출력 s3_prefix_list_id 가 S3 엔드포인트의 접두사 목록이 아니다."
  }
}

run "omits_s3_gateway_endpoint_when_disabled" {
  command = plan

  variables {
    enable_s3_gateway_endpoint = false
  }

  assert {
    condition     = length(aws_vpc_endpoint.s3) == 0 && length(aws_vpc_endpoint_route_table_association.s3) == 0
    error_message = "enable_s3_gateway_endpoint = false 인데 S3 엔드포인트 · 연결이 있다."
  }

  assert {
    condition     = output.s3_prefix_list_id == null
    error_message = "S3 엔드포인트가 없는데 출력 s3_prefix_list_id 가 null 이 아니다."
  }
}

# media_az 를 azs 의 첫 원소가 아닌 값으로 두어, 첫 원소를 내는 구현을 잡는다.
run "outputs_match_contract" {
  command = apply

  variables {
    media_az = "ap-northeast-2c"
  }

  assert {
    condition     = output.vpc_id == aws_vpc.this.id && output.vpc_cidr == "192.0.2.0/24"
    error_message = "출력 vpc_id · vpc_cidr 가 VPC 와 다르다."
  }

  assert {
    condition = (
      output.public_subnet_ids == [for az in var.azs : aws_subnet.public[az].id] &&
      output.private_subnet_ids == [for az in var.azs : aws_subnet.private[az].id]
    )
    error_message = "출력 public_subnet_ids · private_subnet_ids 가 azs 순서의 서브넷 id 가 아니다."
  }

  assert {
    condition = (
      output.media_public_subnet_id == aws_subnet.public["ap-northeast-2c"].id &&
      output.media_private_subnet_id == aws_subnet.private["ap-northeast-2c"].id
    )
    error_message = "출력 media_{public,private}_subnet_id 가 media_az 의 서브넷이 아니다."
  }
}

run "rejects_media_az_outside_azs" {
  command = plan

  variables {
    media_az = "ap-northeast-2b"
  }

  expect_failures = [var.media_az]
}

run "rejects_nat_az_outside_azs" {
  command = plan

  variables {
    nat_az = "ap-northeast-2b"
  }

  expect_failures = [var.nat_az]
}

run "rejects_az_count_other_than_two" {
  command = plan

  variables {
    azs = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
  }

  expect_failures = [var.azs]
}

run "rejects_duplicate_azs" {
  command = plan

  variables {
    azs = ["ap-northeast-2a", "ap-northeast-2a"]
  }

  expect_failures = [var.azs]
}

# 네 등분한 서브넷이 ALB 서브넷 최소(/27)보다 작아지지 않게 VPC 는 /16 부터
# /25 까지만 받는다. /25 는 경계 — 서브넷이 /27 로 나온다.
run "accepts_cidr_slash_25" {
  command = plan

  variables {
    cidr = "192.0.2.0/25"
  }

  assert {
    condition = (
      aws_subnet.public["ap-northeast-2a"].cidr_block == "192.0.2.0/27" &&
      aws_subnet.private["ap-northeast-2c"].cidr_block == "192.0.2.96/27"
    )
    error_message = "/25 VPC 의 서브넷이 /27 네 등분이 아니다."
  }
}

run "rejects_cidr_slash_26" {
  command = plan

  variables {
    cidr = "192.0.2.0/26"
  }

  expect_failures = [var.cidr]
}

# 호스트 비트가 남은 값(네트워크 주소가 아님)은 받지 않는다.
run "rejects_cidr_with_host_bits" {
  command = plan

  variables {
    cidr = "192.0.2.5/24"
  }

  expect_failures = [var.cidr]
}

run "rejects_cidr_larger_than_slash_16" {
  command = plan

  variables {
    cidr = "192.0.0.0/15"
  }

  expect_failures = [var.cidr]
}

# 인터페이스 엔드포인트(POK-408 설계 보강 3-1). 기본은 빈 목록이라 아무것도
# 만들지 않는다 — 모듈의 지금 동작이 그대로다.
run "omits_interface_endpoints_by_default" {
  command = plan

  assert {
    condition     = length(var.interface_endpoint_services) == 0 && var.interface_endpoint_private_dns == true
    error_message = "interface_endpoint_services 기본이 빈 목록이 아니거나 interface_endpoint_private_dns 기본이 true 가 아니다."
  }

  assert {
    condition = (
      length(aws_vpc_endpoint.interface) == 0 &&
      try(aws_security_group.interface_endpoints[0].name, null) == null &&
      try(aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].from_port, null) == null
    )
    error_message = "목록이 비었는데 인터페이스 엔드포인트 · 엔드포인트 SG · 인그레스 규칙이 하나라도 있다."
  }

  assert {
    condition = (
      output.interface_endpoint_sg_id == null &&
      length(output.interface_endpoint_services) == 0 &&
      output.interface_endpoint_private_dns == true
    )
    error_message = "목록이 비었는데 출력이 null · 빈 목록 · true 가 아니다."
  }
}

# nat_az 를 azs 의 첫 원소 · media_az 와 다른 값으로 두어, 다른 AZ 의 프라이빗
# 서브넷을 고르는 구현을 잡는다. 목록은 정렬하지 않은 차례로 넘긴다(출력 정렬).
run "creates_interface_endpoints_in_nat_az_private_subnet" {
  command = apply

  variables {
    nat_az                      = "ap-northeast-2c"
    interface_endpoint_services = ["sqs", "ecr.api", "logs", "ecr.dkr", "secretsmanager"]
  }

  assert {
    condition     = toset(keys(aws_vpc_endpoint.interface)) == toset(["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"])
    error_message = "인터페이스 엔드포인트 키 집합이 넘긴 서비스 다섯과 다르다."
  }

  assert {
    condition = alltrue([
      for service, endpoint in aws_vpc_endpoint.interface :
      endpoint.service_name == "com.amazonaws.ap-northeast-2.${service}" &&
      endpoint.vpc_endpoint_type == "Interface" &&
      endpoint.vpc_id == aws_vpc.this.id &&
      endpoint.tags["Name"] == "example-${service}"
    ])
    error_message = "인터페이스 엔드포인트가 provider 리전의 서비스 이름 · Interface 형 · 이 VPC · Name <name>-<서비스> 꼴이 아니다."
  }

  assert {
    condition     = alltrue([for endpoint in aws_vpc_endpoint.interface : endpoint.private_dns_enabled == true])
    error_message = "interface_endpoint_private_dns 기본(true)인데 private DNS 가 꺼진 엔드포인트가 있다."
  }

  assert {
    condition = alltrue([
      for endpoint in aws_vpc_endpoint.interface :
      endpoint.subnet_ids == toset([aws_subnet.private["ap-northeast-2c"].id])
    ])
    error_message = "인터페이스 엔드포인트의 서브넷이 nat_az 의 프라이빗 서브넷 하나가 아니다."
  }

  assert {
    condition = alltrue([
      for endpoint in aws_vpc_endpoint.interface :
      endpoint.security_group_ids == toset([aws_security_group.interface_endpoints[0].id])
    ])
    error_message = "인터페이스 엔드포인트의 SG 가 엔드포인트 SG 하나가 아니다."
  }
}

# 엔드포인트 ENI 는 연결을 먼저 열지 않는다 — 인그레스는 VPC CIDR 의 443 하나,
# 이그레스는 없다(SG 의 인라인 규칙도 없다).
run "allows_only_vpc_https_into_interface_endpoints" {
  command = apply

  variables {
    interface_endpoint_services = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
  }

  assert {
    condition = (
      aws_security_group.interface_endpoints[0].name == "example-vpce" &&
      aws_security_group.interface_endpoints[0].vpc_id == aws_vpc.this.id &&
      length(aws_security_group.interface_endpoints[0].ingress) == 0 &&
      length(aws_security_group.interface_endpoints[0].egress) == 0
    )
    error_message = "엔드포인트 SG 가 이 VPC 의 <name>-vpce 가 아니거나 인라인 규칙을 가진다."
  }

  assert {
    condition = (
      length(aws_vpc_security_group_ingress_rule.interface_endpoints_https) == 1 &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].security_group_id == aws_security_group.interface_endpoints[0].id &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].from_port == 443 &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].to_port == 443
    )
    error_message = "엔드포인트 SG 인그레스가 TCP 443 규칙 하나가 아니다."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].cidr_ipv4 == "192.0.2.0/24" &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].cidr_ipv6 == null &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].prefix_list_id == null &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].referenced_security_group_id == null
    )
    error_message = "엔드포인트 SG 인그레스 출처가 VPC CIDR 하나가 아니다."
  }

  assert {
    condition = (
      output.interface_endpoint_sg_id == aws_security_group.interface_endpoints[0].id &&
      output.interface_endpoint_services == tolist(["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]) &&
      output.interface_endpoint_private_dns == true
    )
    error_message = "출력 interface_endpoint_{sg_id,services,private_dns} 가 엔드포인트 SG · 정렬한 서비스 목록 · true 가 아니다."
  }
}

# 되돌리기 스위치(설계 3-4) — private DNS 만 끄고 엔드포인트 · SG · 규칙은 둔다.
run "keeps_endpoints_and_sg_when_private_dns_disabled" {
  command = plan

  variables {
    interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
    interface_endpoint_private_dns = false
  }

  assert {
    condition = (
      length(aws_vpc_endpoint.interface) == 5 &&
      alltrue([for endpoint in aws_vpc_endpoint.interface : endpoint.private_dns_enabled == false])
    )
    error_message = "interface_endpoint_private_dns = false 인데 엔드포인트 다섯이 모두 private DNS 를 끄지 않았다."
  }

  assert {
    condition = (
      aws_security_group.interface_endpoints[0].name == "example-vpce" &&
      length(aws_vpc_security_group_ingress_rule.interface_endpoints_https) == 1 &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].from_port == 443 &&
      aws_vpc_security_group_ingress_rule.interface_endpoints_https[0].cidr_ipv4 == "192.0.2.0/24"
    )
    error_message = "interface_endpoint_private_dns = false 인데 엔드포인트 SG · 인그레스 규칙이 그대로가 아니다."
  }

  assert {
    condition     = output.interface_endpoint_private_dns == false
    error_message = "출력 interface_endpoint_private_dns 가 입력(false)과 다르다."
  }
}

run "rejects_duplicate_interface_endpoint_services" {
  command = plan

  variables {
    interface_endpoint_services = ["sqs", "logs", "sqs"]
  }

  expect_failures = [var.interface_endpoint_services]
}

# 서비스 접미만 받는다 — 전체 서비스 이름(리전의 하이픈)은 꼴 위반이다.
run "rejects_full_interface_endpoint_service_name" {
  command = plan

  variables {
    interface_endpoint_services = ["com.amazonaws.ap-northeast-2.sqs"]
  }

  expect_failures = [var.interface_endpoint_services]
}

run "rejects_empty_interface_endpoint_service_segment" {
  command = plan

  variables {
    interface_endpoint_services = ["ecr..api"]
  }

  expect_failures = [var.interface_endpoint_services]
}
