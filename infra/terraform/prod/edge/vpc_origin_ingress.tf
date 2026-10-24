# 내부 ALB 인그레스 ← CloudFront VPC origin(설계 3-2 · 계획 M10 c8 — platform
# 이 이 규칙을 edge 에 넘겼다). CloudFront 는 VPC origin 을 처음 만들 때 그
# VPC 에 관리형 SG CloudFront-VPCOrigins-Service-SG 를 만든다. 그래서 조회는
# VPC origin 생성 뒤여야 한다.
#
# 조회는 media 모듈 전체가 아니라 VPC origin 하나만 기다린다. 모듈 전체를
# 기다리면 VPC origin 밖 변경(표준 로그 끄기 · 비헤이비어 TTL)마다 조회가
# apply 로 밀려, plan 에 이 규칙의 변경 예고가 끼어든다.

resource "terraform_data" "vpc_origin_ready" {
  input = module.media.vpc_origin_id
}

data "aws_security_group" "vpc_origin_service" {
  provider = aws.apne2

  name   = "CloudFront-VPCOrigins-Service-SG"
  vpc_id = local.network.vpc_id

  depends_on = [terraform_data.vpc_origin_ready]
}

resource "aws_vpc_security_group_ingress_rule" "internal_alb_from_vpc_origins" {
  provider = aws.apne2

  security_group_id            = local.platform.internal_alb_sg_id
  description                  = "HTTPS from CloudFront VPC origin"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = data.aws_security_group.vpc_origin_service.id
}
