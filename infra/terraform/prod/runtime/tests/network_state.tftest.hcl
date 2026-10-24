# prod/runtime 뼈대 — network 원격 상태에서 꺼낸 locals 넷, 원격 상태 위치
# 기본값, default_tags 네 키를 단언한다. network 출력은 가짜 값이다 — 값마다
# 달라서 local 이 다른 출력(예: private_subnet_ids 의 원소)을 가리키면 깨진다.
# override_data 가 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

mock_provider "aws" {
  source = "./tests/mocks"
}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id                  = "vpc-0example"
      private_subnet_ids      = ["subnet-0examplea", "subnet-0examplec"]
      media_az                = "ap-northeast-2c"
      media_private_subnet_id = "subnet-0examplemedia"
    }
  }
}

run "exposes_network_outputs_as_locals" {
  command = plan

  assert {
    condition     = local.vpc_id == "vpc-0example"
    error_message = "local.vpc_id 가 network 출력 vpc_id 와 다르다."
  }

  assert {
    condition     = tolist(local.private_subnet_ids) == tolist(["subnet-0examplea", "subnet-0examplec"])
    error_message = "local.private_subnet_ids 가 network 출력 private_subnet_ids 와 다르다."
  }

  assert {
    condition     = local.media_az == "ap-northeast-2c"
    error_message = "local.media_az 가 network 출력 media_az 와 다르다."
  }

  assert {
    condition     = local.media_private_subnet_id == "subnet-0examplemedia"
    error_message = "local.media_private_subnet_id 가 network 출력 media_private_subnet_id 와 다르다."
  }
}

# auth 허용 대역의 출처(POK-429 · K4). 이 파일의 media_private_subnet_id 는
# private_subnet_ids 의 어느 원소와도 달라서, 조회가 다른 서브넷을 가리키면
# 깨진다.
run "looks_up_media_private_subnet" {
  command = plan

  assert {
    condition     = data.aws_subnet.media_private.id == "subnet-0examplemedia"
    error_message = "data.aws_subnet.media_private 가 network 출력 media_private_subnet_id 를 조회하지 않는다."
  }
}

# 원격 상태 위치는 변수 기본값으로 고정한다(CONTRACT-runtime.md 「상태 위치」).
run "reads_network_state_from_prod_state_bucket" {
  command = plan

  assert {
    condition = (
      var.network_state_bucket == "pokeclip-tfstate-prod-apne2" &&
      var.network_state_key == "prod/network/terraform.tfstate"
    )
    error_message = "network 원격 상태 위치 기본값이 pokeclip-tfstate-prod-apne2 · prod/network/terraform.tfstate 가 아니다."
  }

  assert {
    condition = (
      data.terraform_remote_state.network.backend == "s3" &&
      data.terraform_remote_state.network.config.bucket == var.network_state_bucket &&
      data.terraform_remote_state.network.config.key == var.network_state_key &&
      data.terraform_remote_state.network.config.region == var.region
    )
    error_message = "network 원격 상태가 s3 · 변수의 버킷 · 키 · 리전을 읽지 않는다."
  }
}

# mock 은 default_tags 를 tags_all 에 합치지 않는다. 그래서 provider 에 넘기는
# 태그 지도(local)를 직접 본다.
run "tags_resources_as_prod_runtime_root" {
  command = plan

  assert {
    condition = local.default_tags == {
      Project       = "pokeclip"
      Environment   = "prod"
      ManagedBy     = "terraform"
      TerraformRoot = "prod/runtime"
    }
    error_message = "default_tags 가 네 키(Project · Environment · ManagedBy · TerraformRoot = prod/runtime)와 다르다."
  }
}
