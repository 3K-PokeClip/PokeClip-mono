# prod 전용 VPC(설계 3-1 의 ④). 런타임 1단(RDS) · platform · media · edge 가
# 이 루트의 출력을 쓴다 — 의존은 network → 런타임 1단 → platform 한쪽이다.

module "network" {
  source = "../../modules/network"

  name                       = "pokeclip-prod"
  cidr                       = var.cidr
  azs                        = var.azs
  media_az                   = var.media_az
  nat_az                     = var.nat_az
  enable_s3_gateway_endpoint = true
}
