# prod/network 의 출력 여덟(설계 3-2 — 런타임 1단 · platform · media · edge 와의
# 계약)이 모듈 출력과 짝이 맞는지 단언한다. CIDR · AZ 는 가짜 값이다.

mock_provider "aws" {}

override_data {
  target = module.network.data.aws_region.current
  values = {
    region = "ap-northeast-2"
  }
}

variables {
  cidr     = "192.0.2.0/24"
  azs      = ["ap-northeast-2a", "ap-northeast-2c"]
  media_az = "ap-northeast-2c"
  nat_az   = "ap-northeast-2c"
}

# id 는 계산 속성이다. apply 로 돌려 mock 이 자원마다 다른 id 를 만들게 한다.
run "outputs_match_consumer_contract" {
  command = apply

  assert {
    condition     = output.vpc_id == module.network.vpc_id && output.vpc_cidr == "192.0.2.0/24"
    error_message = "출력 vpc_id · vpc_cidr 가 모듈 출력과 다르다."
  }

  assert {
    condition = (
      output.public_subnet_ids == module.network.public_subnet_ids &&
      output.private_subnet_ids == module.network.private_subnet_ids &&
      length(output.public_subnet_ids) == 2 && length(output.private_subnet_ids) == 2
    )
    error_message = "출력 public_subnet_ids · private_subnet_ids 가 모듈 출력(2개씩)과 다르다."
  }

  assert {
    condition = (
      output.media_az == "ap-northeast-2c" &&
      output.media_public_subnet_id == module.network.media_public_subnet_id &&
      output.media_private_subnet_id == module.network.media_private_subnet_id
    )
    error_message = "출력 media_az · media_{public,private}_subnet_id 가 입력 · 모듈 출력과 다르다."
  }

  # prod 는 S3 Gateway EP 를 늘 둔다(설계 3-1 의 ④).
  assert {
    condition     = output.s3_prefix_list_id != null && output.s3_prefix_list_id == module.network.s3_prefix_list_id
    error_message = "출력 s3_prefix_list_id 가 없거나 모듈 출력과 다르다(S3 Gateway EP 꺼짐)."
  }
}
