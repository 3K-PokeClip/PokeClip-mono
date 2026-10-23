# rewind 버킷(POK-308 · 설계 D10) — 이름 기본값 · 삭제 시 객체 보존 ·
# 공개 차단 넷 · ACL 끔 · SSE-S3 · 수명주기 규칙 하나 · 출력 셋을 단언한다.
# 버전 관리 자원과 버킷 정책은 두지 않는다(결정 50 · 정책은 2단) — 없는 자원은
# 시험이 참조할 수 없어 커밋 전 grep 으로 확인한다.
# override_data 가 network 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

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
      media_private_subnet_id = "subnet-0examplec"
    }
  }
}

# 버킷의 id · arn · 지역 도메인 이름은 계산 속성이라 plan 단계에서 고정한다.
# 하위 자원의 bucket 인자와 출력이 이 값을 그대로 받는지 본다.
override_resource {
  target          = aws_s3_bucket.rewind
  override_during = plan
  values = {
    id                          = "pokeclip-prod-rewind-apne2"
    arn                         = "arn:aws:s3:::pokeclip-prod-rewind-apne2"
    bucket_regional_domain_name = "pokeclip-prod-rewind-apne2.s3.ap-northeast-2.amazonaws.com"
  }
}

# 수명주기 filter 의 prefix · object_size_* 는 설정하지 않으면 provider 가
# 계산하는 속성이라 plan 단계에서 미지값이다. override 는 설정이 비운 속성만
# 채우므로, 표지값(prefix "" · 크기 -1)이 그대로 보이면 설정에 그 조건이 없다는
# 뜻이다. 설정에 접두 · 크기 조건을 넣으면 그 값이 보여 단언이 깨진다.
override_resource {
  target          = aws_s3_bucket_lifecycle_configuration.rewind
  override_during = plan
  values = {
    rule = {
      filter = {
        prefix                   = ""
        object_size_greater_than = -1
        object_size_less_than    = -1
      }
    }
  }
}

run "names_bucket_from_variable" {
  command = plan

  assert {
    condition     = var.rewind_bucket_name == "pokeclip-prod-rewind-apne2"
    error_message = "rewind_bucket_name 기본값이 pokeclip-prod-rewind-apne2 가 아니다."
  }

  assert {
    condition     = aws_s3_bucket.rewind.bucket == var.rewind_bucket_name
    error_message = "버킷 이름이 변수 rewind_bucket_name 과 다르다."
  }

  assert {
    condition     = aws_s3_bucket.rewind.force_destroy == false
    error_message = "force_destroy 가 false 가 아니다 — 객체가 든 버킷을 지울 수 있게 된다."
  }
}

run "follows_bucket_name_variable" {
  command = plan

  variables {
    rewind_bucket_name = "pokeclip-prod-rewind-example"
  }

  assert {
    condition     = aws_s3_bucket.rewind.bucket == "pokeclip-prod-rewind-example"
    error_message = "버킷 이름이 변수 rewind_bucket_name 을 따르지 않는다."
  }
}

run "blocks_public_access_and_acls" {
  command = plan

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.rewind.block_public_acls,
      aws_s3_bucket_public_access_block.rewind.block_public_policy,
      aws_s3_bucket_public_access_block.rewind.ignore_public_acls,
      aws_s3_bucket_public_access_block.rewind.restrict_public_buckets,
    ])
    error_message = "퍼블릭 차단 네 항목이 모두 true 가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.rewind.bucket == aws_s3_bucket.rewind.id
    error_message = "퍼블릭 차단이 rewind 버킷에 걸려 있지 않다."
  }

  assert {
    condition = one([
      for rule in aws_s3_bucket_ownership_controls.rewind.rule :
      rule.object_ownership
    ]) == "BucketOwnerEnforced"
    error_message = "객체 소유권이 BucketOwnerEnforced(ACL 끔)가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_ownership_controls.rewind.bucket == aws_s3_bucket.rewind.id
    error_message = "소유권 설정이 rewind 버킷에 걸려 있지 않다."
  }
}

run "encrypts_with_sse_s3" {
  command = plan

  assert {
    condition = one([
      for rule in aws_s3_bucket_server_side_encryption_configuration.rewind.rule :
      rule.apply_server_side_encryption_by_default[0].sse_algorithm
    ]) == "AES256"
    error_message = "기본 암호화가 SSE-S3(AES256) 규칙 하나가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_server_side_encryption_configuration.rewind.bucket == aws_s3_bucket.rewind.id
    error_message = "기본 암호화가 rewind 버킷에 걸려 있지 않다."
  }
}

# 되감기 세그먼트는 지우지 않는다(만료 없음). 끝나지 않은 멀티파트 업로드만
# 7일 뒤 중단한다.
#
# rule 목록 · 원소를 통째로 참조하지 않고 잎 속성만 색인으로 가리킨다. 목록
# 전체가 단언 실패 진단에 실리면 Terraform 1.16.4 가 「value has marks」 로
# panic 한다(provider 가 표지를 붙인 값이 rule 안에 있다).
run "aborts_incomplete_uploads_without_expiration" {
  command = plan

  assert {
    condition = (
      aws_s3_bucket_lifecycle_configuration.rewind.rule[0].id == "abort-incomplete-multipart-upload" &&
      try(aws_s3_bucket_lifecycle_configuration.rewind.rule[1].id, null) == null
    )
    error_message = "수명주기 규칙이 abort-incomplete-multipart-upload 하나가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_lifecycle_configuration.rewind.rule[0].status == "Enabled"
    error_message = "수명주기 규칙이 Enabled 가 아니다."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.rewind.rule[0].abort_incomplete_multipart_upload) == 1 &&
      aws_s3_bucket_lifecycle_configuration.rewind.rule[0].abort_incomplete_multipart_upload[0].days_after_initiation == 7
    )
    error_message = "미완료 멀티파트 업로드 중단이 7일이 아니다."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.rewind.rule[0].expiration) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.rewind.rule[0].noncurrent_version_expiration) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.rewind.rule[0].transition) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.rewind.rule[0].noncurrent_version_transition) == 0
    )
    error_message = "수명주기 규칙에 만료 · 전환이 있다 — 되감기 세그먼트가 지워지거나 옮겨진다."
  }

  # 빈 filter 는 버킷 전체가 대상이다(접두 · 크기 표지값은 파일 머리 override).
  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter) == 1 &&
      aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter[0].prefix == "" &&
      aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter[0].object_size_greater_than == -1 &&
      aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter[0].object_size_less_than == -1 &&
      length(aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter[0].tag) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter[0].and) == 0
    )
    error_message = "수명주기 규칙의 filter 가 버킷 전체(접두 · 크기 · 태그 · and 조건 없음)가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_lifecycle_configuration.rewind.bucket == aws_s3_bucket.rewind.id
    error_message = "수명주기 설정이 rewind 버킷에 걸려 있지 않다."
  }
}

# 출력은 edge(오리진) · media(업로드 정책) 가 읽는다(CONTRACT-runtime.md).
run "outputs_match_bucket_attributes" {
  command = plan

  assert {
    condition = (
      output.rewind_bucket_name == aws_s3_bucket.rewind.bucket &&
      output.rewind_bucket_name == "pokeclip-prod-rewind-apne2"
    )
    error_message = "출력 rewind_bucket_name 이 버킷 이름과 다르다."
  }

  assert {
    condition = (
      output.rewind_bucket_arn == aws_s3_bucket.rewind.arn &&
      output.rewind_bucket_arn == "arn:aws:s3:::pokeclip-prod-rewind-apne2"
    )
    error_message = "출력 rewind_bucket_arn 이 버킷 ARN 과 다르다."
  }

  assert {
    condition = (
      output.rewind_bucket_regional_domain_name == aws_s3_bucket.rewind.bucket_regional_domain_name &&
      output.rewind_bucket_regional_domain_name == "pokeclip-prod-rewind-apne2.s3.ap-northeast-2.amazonaws.com"
    )
    error_message = "출력 rewind_bucket_regional_domain_name 이 버킷 지역 도메인 이름과 다르다."
  }
}
