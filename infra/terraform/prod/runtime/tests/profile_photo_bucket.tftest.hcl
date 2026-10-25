# 프로필 사진 버킷(POK-427 · 설계 D5 · K8) — 이름 기본값 · 삭제 시 객체 보존 ·
# 공개 차단 넷 · ACL 끔 · SSE-S3 · 수명주기 규칙 하나(미완료 멀티파트 7일 · 만료
# 없음)를 단언한다. 버전 관리 · 버킷 정책 · CORS 는 두지 않는다(결정 50 · 정책은
# 2단 · 올리기는 auth 서버가 함). 버킷 자원 안의 versioning 블록 · policy 인자 ·
# cors_rule 블록이 없음은 단언하지만, 별도 자원(aws_s3_bucket_versioning ·
# aws_s3_bucket_policy · aws_s3_bucket_cors_configuration)은 없는 자원을 시험이
# 참조할 수 없어 커밋 전 grep 으로 확인한다. override_data 가 network 원격
# 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

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

# auth_oauth_apps 는 기본값이 없다(D8 — tfvars 필수). 이 파일의 모든 run 에
# 흐르는 예시 값이다.
variables {
  auth_oauth_apps = {
    google  = { client_id = "example-google-client-id", redirect_uri = "https://app.pokeclip.com/auth/callback" }
    chzzk   = { client_id = "example-chzzk-client-id", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
    youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
    soop    = { client_id = "example-soop-client-id", redirect_uri = "https://app.pokeclip.com/oauth/soop/callback" }
  }
}

# 버킷의 id · arn 과 비운 policy 는 계산 속성이라 plan 단계에서 고정한다(rewind
# 시험과 같은 꼴 — JSON 이 아닌 표지값은 설정값일 수 없다).
override_resource {
  target          = aws_s3_bucket.profile_photo
  override_during = plan
  values = {
    id     = "pokeclip-prod-profile-photos-apne2"
    arn    = "arn:aws:s3:::pokeclip-prod-profile-photos-apne2"
    policy = "unset-policy-marker-a"
  }
}

# 수명주기 filter 의 접두 · 크기 표지값(rewind 시험과 같은 꼴 — 두 번째 표지값
# run 이 같은 값을 적은 설정을 가린다).
override_resource {
  target          = aws_s3_bucket_lifecycle_configuration.profile_photo
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

run "names_profile_photo_bucket_from_variable" {
  command = plan

  assert {
    condition     = var.profile_photo_bucket_name == "pokeclip-prod-profile-photos-apne2"
    error_message = "profile_photo_bucket_name 기본값이 pokeclip-prod-profile-photos-apne2 가 아니다."
  }

  assert {
    condition     = aws_s3_bucket.profile_photo.bucket == var.profile_photo_bucket_name
    error_message = "버킷 이름이 변수 profile_photo_bucket_name 과 다르다."
  }

  assert {
    condition     = aws_s3_bucket.profile_photo.force_destroy == false
    error_message = "force_destroy 가 false 가 아니다 — 객체가 든 버킷을 지울 수 있게 된다."
  }
}

run "follows_profile_photo_bucket_name_variable" {
  command = plan

  variables {
    profile_photo_bucket_name = "pokeclip-prod-profile-photos-example"
  }

  assert {
    condition     = aws_s3_bucket.profile_photo.bucket == "pokeclip-prod-profile-photos-example"
    error_message = "버킷 이름이 변수 profile_photo_bucket_name 을 따르지 않는다."
  }
}

# 폐지 예고 인자라 값에 표지가 붙어, 단언이 깨지면 Terraform 1.16.4 가
# 「value has marks」 로 panic 한다 — panic 도 실패다(rewind 시험과 같음).
run "keeps_versioning_policy_and_cors_off_the_profile_photo_bucket" {
  command = plan

  assert {
    condition     = length(aws_s3_bucket.profile_photo.versioning) == 0
    error_message = "버킷에 versioning 블록이 있다 — 버전 관리는 두지 않는다(결정 50)."
  }

  assert {
    condition     = aws_s3_bucket.profile_photo.policy == "unset-policy-marker-a"
    error_message = "버킷에 policy 가 설정돼 있다 — 버킷 정책은 런타임 2단이 만든다(K8)."
  }

  assert {
    condition     = length(aws_s3_bucket.profile_photo.cors_rule) == 0
    error_message = "버킷에 cors_rule 블록이 있다 — 올리기는 auth 서버가 한다(D5)."
  }
}

run "blocks_public_access_and_acls_on_profile_photo_bucket" {
  command = plan

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.profile_photo.block_public_acls,
      aws_s3_bucket_public_access_block.profile_photo.block_public_policy,
      aws_s3_bucket_public_access_block.profile_photo.ignore_public_acls,
      aws_s3_bucket_public_access_block.profile_photo.restrict_public_buckets,
    ])
    error_message = "퍼블릭 차단 네 항목이 모두 true 가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.profile_photo.bucket == aws_s3_bucket.profile_photo.id
    error_message = "퍼블릭 차단이 프로필 사진 버킷에 걸려 있지 않다."
  }

  assert {
    condition = one([
      for rule in aws_s3_bucket_ownership_controls.profile_photo.rule :
      rule.object_ownership
    ]) == "BucketOwnerEnforced"
    error_message = "객체 소유권이 BucketOwnerEnforced(ACL 끔)가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_ownership_controls.profile_photo.bucket == aws_s3_bucket.profile_photo.id
    error_message = "소유권 설정이 프로필 사진 버킷에 걸려 있지 않다."
  }
}

run "encrypts_profile_photo_bucket_with_sse_s3" {
  command = plan

  assert {
    condition = one([
      for rule in aws_s3_bucket_server_side_encryption_configuration.profile_photo.rule :
      rule.apply_server_side_encryption_by_default[0].sse_algorithm
    ]) == "AES256"
    error_message = "기본 암호화가 SSE-S3(AES256) 규칙 하나가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_server_side_encryption_configuration.profile_photo.bucket == aws_s3_bucket.profile_photo.id
    error_message = "기본 암호화가 프로필 사진 버킷에 걸려 있지 않다."
  }
}

# 사진은 지우지 않는다(만료 없음) — 슬롯은 덮어쓰고 탈퇴 때 auth 가 지운다(D5).
# 끝나지 않은 멀티파트 업로드만 7일 뒤 중단한다. 잎 속성만 색인으로 가리킨다
# (rewind 시험과 같은 이유 — marks panic).
run "aborts_incomplete_photo_uploads_without_expiration" {
  command = plan

  assert {
    condition = (
      aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].id == "abort-incomplete-multipart-upload" &&
      try(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[1].id, null) == null
    )
    error_message = "수명주기 규칙이 abort-incomplete-multipart-upload 하나가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].status == "Enabled"
    error_message = "수명주기 규칙이 Enabled 가 아니다."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].abort_incomplete_multipart_upload) == 1 &&
      aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].abort_incomplete_multipart_upload[0].days_after_initiation == 7
    )
    error_message = "미완료 멀티파트 업로드 중단이 7일이 아니다."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].expiration) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].noncurrent_version_expiration) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].transition) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].noncurrent_version_transition) == 0
    )
    error_message = "수명주기 규칙에 만료 · 전환이 있다 — 프로필 사진이 지워지거나 옮겨진다."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter) == 1 &&
      aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter[0].prefix == "" &&
      aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter[0].object_size_greater_than == -1 &&
      aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter[0].object_size_less_than == -1 &&
      length(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter[0].tag) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter[0].and) == 0
    )
    error_message = "수명주기 규칙의 filter 가 버킷 전체(접두 · 크기 · 태그 · and 조건 없음)가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_lifecycle_configuration.profile_photo.bucket == aws_s3_bucket.profile_photo.id
    error_message = "수명주기 설정이 프로필 사진 버킷에 걸려 있지 않다."
  }
}

run "rechecks_photo_lifecycle_filter_with_second_sentinels" {
  command = plan

  override_resource {
    target          = aws_s3_bucket_lifecycle_configuration.profile_photo
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
      aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter[0].prefix == "unset-prefix-marker" &&
      aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter[0].object_size_greater_than == -2 &&
      aws_s3_bucket_lifecycle_configuration.profile_photo.rule[0].filter[0].object_size_less_than == -2
    )
    error_message = "수명주기 filter 에 접두 · 크기 조건이 설정돼 있다(두 번째 표지값과 다르다)."
  }
}
