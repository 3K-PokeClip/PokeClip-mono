# R15: SG 조회가 VPC origin 을 기다리지 않는다(depends_on 없음).
data "aws_security_group" "vpc_origin_service" {
  name   = "CloudFront-VPCOrigins-Service-SG"
  vpc_id = local.network.vpc_id
}
