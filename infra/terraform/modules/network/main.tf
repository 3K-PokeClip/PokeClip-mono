# prod 전용 VPC(설계 2-3 「네트워크」 · 3-1 의 ④). 퍼블릭 · 프라이빗 서브넷을
# AZ 마다 하나씩, NAT 는 nat_az 에 하나만 둔다. 프라이빗 라우트 테이블은 AZ
# 마다 두되 모두 그 NAT 로 나간다 — NAT 를 AZ 마다 늘릴 때 테이블은 그대로 둔다.
# 피어링 · TGW 는 없다(dev VPC 와 SG 참조가 생길 수 없게). 기본 SG 는 다루지
# 않는다.

data "aws_region" "current" {}

locals {
  # VPC 를 네 등분한다 — 앞 둘은 퍼블릭, 뒤 둘은 프라이빗(azs 순서).
  public_cidrs  = { for index, az in var.azs : az => cidrsubnet(var.cidr, 2, index) }
  private_cidrs = { for index, az in var.azs : az => cidrsubnet(var.cidr, 2, index + 2) }
}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.name}-vpc"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-igw"
  }
}

resource "aws_subnet" "public" {
  for_each = local.public_cidrs

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value

  tags = {
    Name = "${var.name}-public-${each.key}"
  }
}

resource "aws_subnet" "private" {
  for_each = local.private_cidrs

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value

  tags = {
    Name = "${var.name}-private-${each.key}"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-public"
  }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# NAT 는 하나지만 키를 nat_az 로 둔다 — AZ 를 바꾸면 주소가 바뀌어 교체가
# plan 에 드러난다.
resource "aws_eip" "nat" {
  for_each = toset([var.nat_az])

  domain = "vpc"

  tags = {
    Name = "${var.name}-nat-${each.key}"
  }
}

resource "aws_nat_gateway" "this" {
  for_each = toset([var.nat_az])

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id

  tags = {
    Name = "${var.name}-nat-${each.key}"
  }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "private" {
  for_each = aws_subnet.private

  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-private-${each.key}"
  }
}

resource "aws_route" "private_nat" {
  for_each = aws_route_table.private

  route_table_id         = each.value.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[var.nat_az].id
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private[each.key].id
}

# 프라이빗 서브넷의 S3 요청(사이드카 rewind 업로드 · ECR 레이어)을 NAT 밖으로
# 보낸다. 연결은 별도 자원이라 키 집합(= 프라이빗)을 단언할 수 있다.
resource "aws_vpc_endpoint" "s3" {
  count = var.enable_s3_gateway_endpoint ? 1 : 0

  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"

  tags = {
    Name = "${var.name}-s3"
  }
}

resource "aws_vpc_endpoint_route_table_association" "s3" {
  for_each = var.enable_s3_gateway_endpoint ? aws_route_table.private : {}

  vpc_endpoint_id = aws_vpc_endpoint.s3[0].id
  route_table_id  = each.value.id
}

# AWS API 를 NAT 밖 VPC 인터페이스 엔드포인트로 받는다(POK-408 설계 보강 3-1).
# ENI 는 nat_az 의 프라이빗 서브넷 하나에만 둔다 — 그 AZ 가 손상되면 다른 AZ 도
# 이 서비스들을 잃는다(NAT 와 같은 장애 영역). 정책은 붙이지 않는다(기본 전체
# 허용).
locals {
  interface_endpoint_count = length(var.interface_endpoint_services) > 0 ? 1 : 0
}

# 엔드포인트 ENI 는 연결을 먼저 열지 않으므로 이그레스 규칙을 두지 않는다
# (Terraform 이 새 SG 의 기본 이그레스를 지운다).
resource "aws_security_group" "interface_endpoints" {
  count = local.interface_endpoint_count

  name        = "${var.name}-vpce"
  description = "VPC interface endpoints - HTTPS from the VPC"
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name}-vpce"
  }
}

resource "aws_vpc_security_group_ingress_rule" "interface_endpoints_https" {
  count = local.interface_endpoint_count

  security_group_id = aws_security_group.interface_endpoints[0].id
  description       = "HTTPS from the VPC"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = aws_vpc.this.cidr_block
}

resource "aws_vpc_endpoint" "interface" {
  for_each = toset(var.interface_endpoint_services)

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${data.aws_region.current.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = var.interface_endpoint_private_dns
  subnet_ids          = [aws_subnet.private[var.nat_az].id]
  security_group_ids  = [aws_security_group.interface_endpoints[0].id]

  tags = {
    Name = "${var.name}-${each.key}"
  }

  # 엔드포인트가 생기는 것이 곧 private DNS 전환이다. 인그레스 규칙이 먼저 있어야
  # 전환 순간부터 VPC 호출자가 닿고, 파괴 때는 엔드포인트가 먼저 사라진다.
  depends_on = [aws_vpc_security_group_ingress_rule.interface_endpoints_https]
}
