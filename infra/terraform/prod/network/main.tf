# prod 전용 VPC(설계 3-1 의 ④). 런타임 1단(RDS) · platform · media · edge 가
# 이 루트의 출력을 쓴다 — 의존은 network → 런타임 1단 → platform 한쪽이다.
#
# 인터페이스 엔드포인트 다섯은 Fargate 기동(ECR · 로그 · 비밀)과 일꾼의 SQS 를
# 덮는다(POK-408 — README 「인터페이스 엔드포인트」). private DNS 가 켜지면 VPC
# 의 모든 호출자가 이 다섯 이름을 엔드포인트로 푼다. SSM · STS · KMS 는 넣지
# 않는다 — Media 상자의 SSM 은 지금처럼 NAT 로 간다.

module "network" {
  source = "../../modules/network"

  name                       = "pokeclip-prod"
  cidr                       = var.cidr
  azs                        = var.azs
  media_az                   = var.media_az
  nat_az                     = var.nat_az
  enable_s3_gateway_endpoint = true

  interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
  interface_endpoint_private_dns = var.interface_endpoint_private_dns
}
