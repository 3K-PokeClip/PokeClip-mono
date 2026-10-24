# clips 버킷(POK-408 · 설계 D3 · K4 · K8) — 이름 기본값 · 삭제 시 객체 보존 ·
# 공개 차단 넷 · ACL 끔 · SSE-S3 · 수명주기 규칙 둘(만료 60일 · 미완료 멀티파트
# 7일)을 단언한다. 버전 관리 · 버킷 정책 · CORS 는 두지 않는다(결정 50 ·
# 정책은 2단 · 웹은 미리서명 주소를 <video> · <img> 로만 씀). 버킷 자원 안의
# versioning 블록 · policy 인자가 없음은 단언하지만, 별도 자원
# (aws_s3_bucket_versioning · aws_s3_bucket_policy · aws_s3_bucket_cors_configuration)
# 은 없는 자원을 시험이 참조할 수 없어 커밋 전 grep 으로 확인한다.
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

# 버킷의 id · arn 은 계산 속성이라 plan 단계에서 고정한다. 하위 자원의 bucket
# 인자가 이 값을 그대로 받는지 본다. policy 도 설정이 비우면 계산 속성이다.
# provider 가 JSON 이 아닌 policy 설정을 거부하므로, JSON 이 아닌 표지값은
# 설정값일 수 없다.
override_resource {
  target          = aws_s3_bucket.clips
  override_during = plan
  values = {
    id     = "pokeclip-prod-clips-apne2"
    arn    = "arn:aws:s3:::pokeclip-prod-clips-apne2"
    policy = "unset-policy-marker-a"
  }
}

# 수명주기 filter 의 prefix · object_size_* 는 설정하지 않으면 provider 가
# 계산하는 속성이라 plan 단계에서 미지값이다. override 는 설정이 비운 속성만
# 채우므로, 표지값이 그대로 보이면 설정에 그 조건이 없다는 뜻이다(rewind 시험과
# 같은 꼴 — 두 번째 표지값 run 이 같은 값을 적은 설정을 가린다).
override_resource {
  target          = aws_s3_bucket_lifecycle_configuration.clips
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

run "names_clips_bucket_from_variable" {
  command = plan

  assert {
    condition     = var.clips_bucket_name == "pokeclip-prod-clips-apne2"
    error_message = "clips_bucket_name 기본값이 pokeclip-prod-clips-apne2 가 아니다."
  }

  assert {
    condition     = aws_s3_bucket.clips.bucket == var.clips_bucket_name
    error_message = "버킷 이름이 변수 clips_bucket_name 과 다르다."
  }

  assert {
    condition     = aws_s3_bucket.clips.force_destroy == false
    error_message = "force_destroy 가 false 가 아니다 — 객체가 든 버킷을 지울 수 있게 된다."
  }
}

run "follows_clips_bucket_name_variable" {
  command = plan

  variables {
    clips_bucket_name = "pokeclip-prod-clips-example"
  }

  assert {
    condition     = aws_s3_bucket.clips.bucket == "pokeclip-prod-clips-example"
    error_message = "버킷 이름이 변수 clips_bucket_name 을 따르지 않는다."
  }
}

# 버킷 자원 안의 버전 관리 · 정책 · CORS 를 쓰지 않는다. 폐지 예고 인자라 값에
# 표지가 붙어, 단언이 깨지면 Terraform 1.16.4 가 「value has marks」 로 panic
# 한다 — panic 도 실패다(rewind 시험과 같음).
run "keeps_versioning_policy_and_cors_off_the_clips_bucket" {
  command = plan

  assert {
    condition     = length(aws_s3_bucket.clips.versioning) == 0
    error_message = "버킷에 versioning 블록이 있다 — 버전 관리는 두지 않는다(결정 50)."
  }

  assert {
    condition     = aws_s3_bucket.clips.policy == "unset-policy-marker-a"
    error_message = "버킷에 policy 가 설정돼 있다 — 버킷 정책은 런타임 2단이 만든다(K8)."
  }

  assert {
    condition     = length(aws_s3_bucket.clips.cors_rule) == 0
    error_message = "버킷에 cors_rule 블록이 있다 — 웹은 미리서명 주소를 <video> · <img> 로만 쓴다(D3)."
  }
}

run "blocks_public_access_and_acls_on_clips_bucket" {
  command = plan

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.clips.block_public_acls,
      aws_s3_bucket_public_access_block.clips.block_public_policy,
      aws_s3_bucket_public_access_block.clips.ignore_public_acls,
      aws_s3_bucket_public_access_block.clips.restrict_public_buckets,
    ])
    error_message = "퍼블릭 차단 네 항목이 모두 true 가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.clips.bucket == aws_s3_bucket.clips.id
    error_message = "퍼블릭 차단이 clips 버킷에 걸려 있지 않다."
  }

  assert {
    condition = one([
      for rule in aws_s3_bucket_ownership_controls.clips.rule :
      rule.object_ownership
    ]) == "BucketOwnerEnforced"
    error_message = "객체 소유권이 BucketOwnerEnforced(ACL 끔)가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_ownership_controls.clips.bucket == aws_s3_bucket.clips.id
    error_message = "소유권 설정이 clips 버킷에 걸려 있지 않다."
  }
}

run "encrypts_clips_bucket_with_sse_s3" {
  command = plan

  assert {
    condition = one([
      for rule in aws_s3_bucket_server_side_encryption_configuration.clips.rule :
      rule.apply_server_side_encryption_by_default[0].sse_algorithm
    ]) == "AES256"
    error_message = "기본 암호화가 SSE-S3(AES256) 규칙 하나가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_server_side_encryption_configuration.clips.bucket == aws_s3_bucket.clips.id
    error_message = "기본 암호화가 clips 버킷에 걸려 있지 않다."
  }
}

# 완성 영상 창고는 60일 수명 규칙이 치운다(K4 — 3번 코드 S3Store.java:66 의
# 전제 · dev 버킷과 같음). 버킷 전체가 대상이다. 끝나지 않은 멀티파트 업로드는
# 7일 뒤 중단한다.
#
# rule 목록 · 원소를 통째로 참조하지 않고 잎 속성만 색인으로 가리킨다. 목록
# 전체가 단언 실패 진단에 실리면 Terraform 1.16.4 가 「value has marks」 로
# panic 한다(rewind 시험과 같음).
run "expires_clips_after_60_days_and_aborts_incomplete_uploads" {
  command = plan

  assert {
    condition = (
      aws_s3_bucket_lifecycle_configuration.clips.rule[0].id == "expire-after-60-days" &&
      aws_s3_bucket_lifecycle_configuration.clips.rule[1].id == "abort-incomplete-multipart-upload" &&
      try(aws_s3_bucket_lifecycle_configuration.clips.rule[2].id, null) == null
    )
    error_message = "수명주기 규칙이 expire-after-60-days · abort-incomplete-multipart-upload 둘이 아니다."
  }

  assert {
    condition = (
      aws_s3_bucket_lifecycle_configuration.clips.rule[0].status == "Enabled" &&
      aws_s3_bucket_lifecycle_configuration.clips.rule[1].status == "Enabled"
    )
    error_message = "수명주기 규칙 가운데 Enabled 가 아닌 것이 있다."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.clips.rule[0].expiration) == 1 &&
      aws_s3_bucket_lifecycle_configuration.clips.rule[0].expiration[0].days == 60 &&
      length(aws_s3_bucket_lifecycle_configuration.clips.rule[0].abort_incomplete_multipart_upload) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.clips.rule[0].transition) == 0
    )
    error_message = "만료 규칙이 60일 만료 하나(전환 · 멀티파트 없음)가 아니다(K4)."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.clips.rule[1].abort_incomplete_multipart_upload) == 1 &&
      aws_s3_bucket_lifecycle_configuration.clips.rule[1].abort_incomplete_multipart_upload[0].days_after_initiation == 7 &&
      length(aws_s3_bucket_lifecycle_configuration.clips.rule[1].expiration) == 0 &&
      length(aws_s3_bucket_lifecycle_configuration.clips.rule[1].transition) == 0
    )
    error_message = "미완료 멀티파트 규칙이 7일 중단 하나(만료 · 전환 없음)가 아니다."
  }

  assert {
    condition = alltrue([
      for i in [0, 1] : (
        length(aws_s3_bucket_lifecycle_configuration.clips.rule[i].noncurrent_version_expiration) == 0 &&
        length(aws_s3_bucket_lifecycle_configuration.clips.rule[i].noncurrent_version_transition) == 0
      )
    ])
    error_message = "수명주기 규칙에 이전 버전 만료 · 전환이 있다 — 버전 관리를 두지 않는다."
  }

  # 빈 filter 는 버킷 전체가 대상이다(접두 · 크기 표지값은 파일 머리 override).
  assert {
    condition = alltrue([
      for i in [0, 1] : (
        length(aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter) == 1 &&
        aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter[0].prefix == "" &&
        aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter[0].object_size_greater_than == -1 &&
        aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter[0].object_size_less_than == -1 &&
        length(aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter[0].tag) == 0 &&
        length(aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter[0].and) == 0
      )
    ])
    error_message = "수명주기 규칙의 filter 가 버킷 전체(접두 · 크기 · 태그 · and 조건 없음)가 아니다."
  }

  assert {
    condition     = aws_s3_bucket_lifecycle_configuration.clips.bucket == aws_s3_bucket.clips.id
    error_message = "수명주기 설정이 clips 버킷에 걸려 있지 않다."
  }
}

# 파일 머리와 다른 표지값으로 덮어 같은 조건 부재를 다시 본다. 설정에 조건이
# 있으면 그 값은 두 run 가운데 적어도 하나의 표지값과 달라 단언이 깨진다.
run "rechecks_clips_lifecycle_filter_with_second_sentinels" {
  command = plan

  override_resource {
    target          = aws_s3_bucket_lifecycle_configuration.clips
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
    condition = alltrue([
      for i in [0, 1] : (
        aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter[0].prefix == "unset-prefix-marker" &&
        aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter[0].object_size_greater_than == -2 &&
        aws_s3_bucket_lifecycle_configuration.clips.rule[i].filter[0].object_size_less_than == -2
      )
    ])
    error_message = "수명주기 filter 에 접두 · 크기 조건이 설정돼 있다(두 번째 표지값과 다르다)."
  }
}
