# 내부 ALB 인그레스 ← CloudFront VPC origin(설계 3-2 · 계획 M10 c8 — platform
# 이 이 규칙을 edge 에 넘겼다). CloudFront 는 VPC origin 을 처음 만들 때 그
# VPC 에 관리형 SG CloudFront-VPCOrigins-Service-SG 를 만든다. 그래서 조회는
# VPC origin(module.media) 생성 뒤여야 한다.

data "aws_security_group" "vpc_origin_service" {
  provider = aws.apne2

  name   = "CloudFront-VPCOrigins-Service-SG"
  vpc_id = local.network.vpc_id

  depends_on = [module.media]
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
