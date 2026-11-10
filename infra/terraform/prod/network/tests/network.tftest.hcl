# prod/network 의 출력 여덟(설계 3-2 — 런타임 1단 · platform · media · edge 와의
# 계약)이 모듈 출력과 짝이 맞는지 단언한다. 인터페이스 엔드포인트(POK-408)는
# 서비스 다섯 · private DNS 스위치가 모듈에 넘어가는지와 새 출력 셋을 본다 —
# 엔드포인트 자원 자체(서브넷 · SG · 이름 꼴)는 modules/network 시험이 본다.
# CIDR · AZ 는 가짜 값이다.

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

# 엔드포인트 다섯(설계 3-2). 정렬된 목록이라 순서까지 같아야 한다.
run "enables_five_interface_endpoints" {
  command = apply

  assert {
    condition     = output.interface_endpoint_services == tolist(["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"])
    error_message = "출력 interface_endpoint_services 가 다섯(ecr.api · ecr.dkr · logs · secretsmanager · sqs)이 아니다."
  }

  assert {
    condition = (
      output.interface_endpoint_services == module.network.interface_endpoint_services &&
      output.interface_endpoint_sg_id != null &&
      output.interface_endpoint_sg_id == module.network.interface_endpoint_sg_id
    )
    error_message = "출력 interface_endpoint_services · interface_endpoint_sg_id 가 모듈 출력과 다르거나 SG 가 없다."
  }

  # 루트 변수의 기본은 켬이다 — tfvars 에 줄이 없으면 private DNS 가 켜진다.
  assert {
    condition     = output.interface_endpoint_private_dns == true && module.network.interface_endpoint_private_dns == true
    error_message = "interface_endpoint_private_dns 의 기본이 true 가 아니거나 모듈에 넘어가지 않는다."
  }
}

# 되돌리기 1단계 — 루트 변수 하나로 모듈의 private DNS 만 끈다. 엔드포인트 ·
# SG 는 그대로다.
run "passes_private_dns_switch_to_module" {
  command = apply

  variables {
    interface_endpoint_private_dns = false
  }

  assert {
    condition     = output.interface_endpoint_private_dns == false && module.network.interface_endpoint_private_dns == false
    error_message = "interface_endpoint_private_dns = false 가 모듈에 넘어가지 않는다."
  }

  assert {
    condition = (
      length(output.interface_endpoint_services) == 5 &&
      output.interface_endpoint_sg_id != null
    )
    error_message = "private DNS 를 끄면 엔드포인트 · SG 가 남아야 한다."
  }
}

# 엔드포인트를 더해도 기존 출력 여덟은 그대로 모듈 출력과 짝이 맞는다(소비 루트
# 무변경 — 설계 3-4 의 2). 스위치를 꺼도 같다.
run "keeps_existing_outputs_with_endpoints" {
  command = apply

  variables {
    interface_endpoint_private_dns = false
  }

  assert {
    condition = (
      output.vpc_id == module.network.vpc_id &&
      output.vpc_cidr == module.network.vpc_cidr &&
      output.public_subnet_ids == module.network.public_subnet_ids &&
      output.private_subnet_ids == module.network.private_subnet_ids &&
      output.media_az == var.media_az &&
      output.media_public_subnet_id == module.network.media_public_subnet_id &&
      output.media_private_subnet_id == module.network.media_private_subnet_id &&
      output.s3_prefix_list_id == module.network.s3_prefix_list_id &&
      output.s3_prefix_list_id != null
    )
    error_message = "엔드포인트를 더하자 기존 출력 여덟 중 하나가 모듈 출력과 어긋난다."
  }
}
