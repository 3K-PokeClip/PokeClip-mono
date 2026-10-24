# prod/network 의 출력을 읽는다(CONTRACT-runtime.md 「network → 런타임 1단」 행).
# 같은 계정 · 같은 상태 버킷이라 변수 파일이 아니라 remote_state 로 받는다.
data "terraform_remote_state" "network" {
  backend = "s3"

  config = {
    bucket = var.network_state_bucket
    key    = var.network_state_key
    region = var.region
  }
}

locals {
  vpc_id                  = data.terraform_remote_state.network.outputs.vpc_id
  private_subnet_ids      = data.terraform_remote_state.network.outputs.private_subnet_ids
  media_az                = data.terraform_remote_state.network.outputs.media_az
  media_private_subnet_id = data.terraform_remote_state.network.outputs.media_private_subnet_id
}

# auth resolve 전용 창구의 허용 대역(POK-429 · K4) — Media 프라이빗 서브넷의
# CIDR. 호출자 권한으로 ec2:DescribeSubnets 를 부른다(점검 묶음 runtime-1a).
data "aws_subnet" "media_private" {
  id = local.media_private_subnet_id
}

locals {
  media_private_subnet_cidr = data.aws_subnet.media_private.cidr_block
}
