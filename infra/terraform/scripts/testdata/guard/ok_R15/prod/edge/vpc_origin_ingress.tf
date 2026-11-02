# R15 통과: SG 조회가 VPC origin 하나(terraform_data.vpc_origin_ready)만
# 기다린다.
data "aws_security_group" "vpc_origin_service" {
  name   = "CloudFront-VPCOrigins-Service-SG"
  vpc_id = local.network.vpc_id

  depends_on = [terraform_data.vpc_origin_ready]
}
