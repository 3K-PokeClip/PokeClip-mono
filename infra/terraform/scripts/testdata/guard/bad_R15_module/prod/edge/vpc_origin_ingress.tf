# R15: SG 조회가 media 모듈 전체를 기다린다(VPC origin 하나가 아님).
data "aws_security_group" "vpc_origin_service" {
  name   = "CloudFront-VPCOrigins-Service-SG"
  vpc_id = local.network.vpc_id

  depends_on = [module.media]
}
