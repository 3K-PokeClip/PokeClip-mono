# rewind 버킷(POK-308 · 설계 D10) — 이름 기본값 · 삭제 시 객체 보존 ·
# 공개 차단 넷 · ACL 끔 · SSE-S3 · 수명주기 규칙 하나 · 출력 셋 · 앱 출처의
# GET · HEAD 만 허락하는 CORS 규칙 하나(POK-427)를 단언한다.
# 버전 관리와 버킷 정책은 두지 않는다(결정 50 · 정책은 2단). 버킷 자원 안의
# versioning 블록 · policy 인자가 없음은 단언하지만, 별도 자원
# (aws_s3_bucket_versioning · aws_s3_bucket_policy)은 없는 자원을 시험이 참조할
# 수 없어 커밋 전 grep 으로 확인한다.
# override_data 가 network 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

mock_provider "aws" {
  source = "./tests/mocks"
}

# auth_oauth_apps 는 기본값이 없다(auth_settings.tf — tfvars 필수). 이 파일의
# 모든 run 에 흐르는 예시 값이다(run 안의 variables 가 덮는 변수만 바뀐다).
variables {
  auth_oauth_apps = {
    google  = { client_id = "example-google-client-id", redirect_uri = "https://app.pokeclip.com/auth/callback" }
    chzzk   = { client_id = "example-chzzk-client-id", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
    youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
    soop    = { client_id = "example-soop-client-id", redirect_uri = "https://app.pokeclip.com/oauth/soop/callback" }
  }
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
# 하위 자원의 bucket 인자와 출력이 이 값을 그대로 받는지 본다. policy 도 설정이
# 비우면 계산 속성이다. provider 가 JSON 이 아닌 policy 설정을 거부하므로, JSON 이
# 아닌 표지값은 설정값일 수 없다.
override_resource {
  target          = aws_s3_bucket.rewind
  override_during = plan
  values = {
    id                          = "pokeclip-prod-rewind-apne2"
    arn                         = "arn:aws:s3:::pokeclip-prod-rewind-apne2"
    bucket_regional_domain_name = "pokeclip-prod-rewind-apne2.s3.ap-northeast-2.amazonaws.com"
    policy                      = "unset-policy-marker-a"
  }
}

# 수명주기 filter 의 prefix · object_size_* 는 설정하지 않으면 provider 가
# 계산하는 속성이라 plan 단계에서 미지값이다. override 는 설정이 비운 속성만
# 채우므로, 표지값이 그대로 보이면 설정에 그 조건이 없다는 뜻이다. 다만 설정이
# 표지값과 같은 값을 적으면 표지값 하나로는 가리지 못한다. 그래서 run
# 「rechecks_lifecycle_filter_with_second_sentinels」 가 다른 표지값으로 한 번 더
# 보고, 두 표지값에 모두 맞는 설정값은 없다.
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

  # 파일 머리 override 는 기본 이름의 값이다. 여기서는 바뀐 이름의 값으로 덮어
  # 출력이 기본값 · 상수가 아니라 버킷 속성을 따르는지 본다.
  override_resource {
    target          = aws_s3_bucket.rewind
    override_during = plan
    values = {
      id                          = "pokeclip-prod-rewind-example"
      arn                         = "arn:aws:s3:::pokeclip-prod-rewind-example"
      bucket_regional_domain_name = "pokeclip-prod-rewind-example.s3.ap-northeast-2.amazonaws.com"
    }
  }

  assert {
    condition     = aws_s3_bucket.rewind.bucket == "pokeclip-prod-rewind-example"
    error_message = "버킷 이름이 변수 rewind_bucket_name 을 따르지 않는다."
  }

  assert {
    condition = (
      output.rewind_bucket_name == "pokeclip-prod-rewind-example" &&
      output.rewind_bucket_arn == "arn:aws:s3:::pokeclip-prod-rewind-example" &&
      output.rewind_bucket_regional_domain_name == "pokeclip-prod-rewind-example.s3.ap-northeast-2.amazonaws.com"
    )
    error_message = "이름을 바꿨는데 출력 셋(이름 · ARN · 지역 도메인 이름)이 바뀐 버킷을 따르지 않는다."
  }
}

# 버킷 자원 안의 버전 관리 · 정책을 쓰지 않는다(결정 50 · 정책은 2단). 둘은
# provider 가 폐지 예고한 인자라 값에 표지가 붙는다. 그래서 단언이 깨지면
# Terraform 1.16.4 가 「value has marks」 로 panic 한다 — panic 도 실패다.
run "keeps_versioning_and_policy_off_the_bucket" {
  command = plan

  assert {
    condition     = length(aws_s3_bucket.rewind.versioning) == 0
    error_message = "버킷에 versioning 블록이 있다 — 버전 관리는 두지 않는다(결정 50)."
  }

  assert {
    condition     = aws_s3_bucket.rewind.policy == "unset-policy-marker-a"
    error_message = "버킷에 policy 가 설정돼 있다 — 버킷 정책은 런타임 2단이 만든다."
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

# 파일 머리와 다른 표지값으로 덮어 같은 조건 부재를 다시 본다. 설정에 조건이
# 있으면 그 값은 두 run 가운데 적어도 하나의 표지값과 달라 단언이 깨진다.
run "rechecks_lifecycle_filter_with_second_sentinels" {
  command = plan

  override_resource {
    target          = aws_s3_bucket_lifecycle_configuration.rewind
    override_during = plan
    values = {
      rule = {
        filter = {
          prefix                   = "unset-prefix-marker"
          object_size_greater_than = -2
          object_size_less_than    = -2
        }
      }
    }
  }

  assert {
    condition = (
      aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter[0].prefix == "unset-prefix-marker" &&
      aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter[0].object_size_greater_than == -2 &&
      aws_s3_bucket_lifecycle_configuration.rewind.rule[0].filter[0].object_size_less_than == -2
    )
    error_message = "수명주기 filter 에 접두 · 크기 조건이 설정돼 있다(두 번째 표지값과 다르다)."
  }
}

# 조각 창고 CORS(CONTRACT-runtime.md 「작업 역할 대상」 — 편집기 트랙별 미리
# 듣기가 clip 이 서명한 조각 주소를 브라우저 fetch 로 받는다, POK-389). 규칙은
# 하나이고 앱 출처의 GET · HEAD 만 허락한다. 쓰기 동작 · 다른 출처 · 요청
# 헤더 허용은 없다 — fetch 가 헤더 없는 단순 요청이라 사전 요청이 없다.
run "allows_app_origin_get_head_on_rewind" {
  command = plan

  assert {
    condition     = var.app_origin == "https://app.pokeclip.com"
    error_message = "app_origin 기본값이 https://app.pokeclip.com 이 아니다."
  }

  assert {
    condition     = aws_s3_bucket_cors_configuration.rewind.bucket == aws_s3_bucket.rewind.id
    error_message = "CORS 설정이 rewind 버킷에 걸려 있지 않다."
  }

  assert {
    condition     = length(aws_s3_bucket_cors_configuration.rewind.cors_rule) == 1
    error_message = "CORS 규칙이 하나가 아니다."
  }

  assert {
    condition     = one(aws_s3_bucket_cors_configuration.rewind.cors_rule).allowed_methods == toset(["GET", "HEAD"])
    error_message = "CORS 허용 메서드가 정확히 GET · HEAD 가 아니다."
  }

  assert {
    condition     = one(aws_s3_bucket_cors_configuration.rewind.cors_rule).allowed_origins == toset(["https://app.pokeclip.com"])
    error_message = "CORS 허용 출처가 앱 출처 하나가 아니다."
  }

  assert {
    condition     = length(coalesce(one(aws_s3_bucket_cors_configuration.rewind.cors_rule).allowed_headers, [])) == 0
    error_message = "CORS 가 요청 헤더를 허락한다 — 미리 듣기 fetch 는 헤더를 싣지 않는다."
  }
}

run "follows_app_origin_variable" {
  command = plan

  variables {
    app_origin = "https://studio.example.com"
  }

  assert {
    condition     = one(aws_s3_bucket_cors_configuration.rewind.cors_rule).allowed_origins == toset(["https://studio.example.com"])
    error_message = "CORS 허용 출처가 변수 app_origin 을 따르지 않는다."
  }
}

# 와일드카드 · http · 경로 · 끝 / 는 출처가 아니거나 모든 사이트를 연다.
run "rejects_wildcard_app_origin" {
  command = plan

  variables {
    app_origin = "*"
  }

  expect_failures = [var.app_origin]
}

run "rejects_http_app_origin" {
  command = plan

  variables {
    app_origin = "http://app.pokeclip.com"
  }

  expect_failures = [var.app_origin]
}

run "rejects_app_origin_with_path" {
  command = plan

  variables {
    app_origin = "https://app.pokeclip.com/"
  }

  expect_failures = [var.app_origin]
}

run "rejects_wildcard_subdomain_app_origin" {
  command = plan

  variables {
    app_origin = "https://*.pokeclip.com"
  }

  expect_failures = [var.app_origin]
}
