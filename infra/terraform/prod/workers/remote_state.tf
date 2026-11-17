# 앞 루트 출력(CONTRACT-runtime.md). 같은 환경 안의 값이라 terraform_remote_state
# 로 읽는다. 런타임 1단 출력의 꼴은 CONTRACT-runtime.md 가 정본이고, 이 루트가
# 쓰는 꼴은 main.tf 의 terraform_data.runtime_contract 가 검사한다.

data "terraform_remote_state" "network" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.network
    region = var.region
  }
}

data "terraform_remote_state" "platform" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.platform
    region = var.region
  }
}

data "terraform_remote_state" "registry" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.registry
    region = var.region
  }
}

data "terraform_remote_state" "runtime" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.runtime
    region = var.region
  }
}

locals {
  network   = data.terraform_remote_state.network.outputs
  platform  = data.terraform_remote_state.platform.outputs
  repo_urls = data.terraform_remote_state.registry.outputs.repo_urls
  runtime   = data.terraform_remote_state.runtime.outputs
}
