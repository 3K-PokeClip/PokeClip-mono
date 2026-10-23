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
