# 출력 app_task_resource_arns(POK-408 · 설계 D10 · K1 · K3) — 서비스 → 키 → ARN
# 지도가 platform 의 task_resource_keys(auth 1 · clip 8)와 배치 루트의 일꾼 키
# (render 4 · upload 2)를 그대로 내는지, 값이 대응 자원 ARN 이고 platform 의 ARN
# 꼴 검사(prod/platform remote_state.tf runtime_contract)를 통과하는지
# 단언한다. ARN 은 mock 이 만드는 가짜 값이다. override_data 가 network 원격
# 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

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

# platform 은 clip 작업 역할의 문장을 이 지도의 키로 만들고, 모르는 키를
# 거부한다. 썸네일 DLQ 키는 내지 않는다(K3 — 아무도 읽지 않음). 일꾼 키는
# 배치 루트가 읽는다(K1). broadcast_events 시험에서 옮긴 run 이다.
run "outputs_task_resource_keys_per_service" {
  command = apply

  assert {
    condition     = toset(keys(output.app_task_resource_arns)) == toset(["auth", "clip", "render", "upload"])
    error_message = "app_task_resource_arns 의 서비스 집합이 auth · clip · render · upload 와 다르다."
  }

  assert {
    condition     = toset(keys(output.app_task_resource_arns["auth"])) == toset(["profile_photo_bucket_arn"])
    error_message = "auth 의 키 집합이 profile_photo_bucket_arn 하나가 아니다(platform task_resource_keys)."
  }

  assert {
    condition = toset(keys(output.app_task_resource_arns["clip"])) == toset([
      "broadcast_queue_arn",
      "render_queue_arn",
      "render_dlq_arn",
      "upload_queue_arn",
      "upload_dlq_arn",
      "thumbnail_queue_arn",
      "clips_bucket_arn",
      "segment_bucket_arn",
    ])
    error_message = "clip 의 키 집합이 platform task_resource_keys 의 여덟과 다르다."
  }

  assert {
    condition = toset(keys(output.app_task_resource_arns["render"])) == toset([
      "render_queue_arn", "thumbnail_queue_arn", "segment_bucket_arn", "clips_bucket_arn",
    ])
    error_message = "render 의 키 집합이 render · thumbnail 큐 · segment · clips 버킷 넷이 아니다."
  }

  assert {
    condition = toset(keys(output.app_task_resource_arns["upload"])) == toset([
      "upload_queue_arn", "clips_bucket_arn",
    ])
    error_message = "upload 의 키 집합이 upload 큐 · clips 버킷 둘이 아니다."
  }

  assert {
    condition = alltrue(flatten([
      for arns in values(output.app_task_resource_arns) : [
        for arn in values(arns) : !strcontains(arn, "*")
      ]
    ]))
    error_message = "app_task_resource_arns 의 값에 와일드카드가 있다."
  }
}

# 키 집합 · 정규식만으로는 잘못 옮긴 ARN 을 못 잡는다 — 키마다 대응 자원을 본다.
run "maps_each_key_to_its_resource_arn" {
  command = apply

  assert {
    condition = length(distinct([
      aws_sqs_queue.broadcast["clip"].arn,
      aws_sqs_queue.job["render"].arn,
      aws_sqs_queue.job_dlq["render"].arn,
      aws_sqs_queue.job["upload"].arn,
      aws_sqs_queue.job_dlq["upload"].arn,
      aws_sqs_queue.job["thumbnail"].arn,
      aws_s3_bucket.clips.arn,
      aws_s3_bucket.rewind.arn,
      aws_s3_bucket.profile_photo.arn,
    ])) == 9
    error_message = "mock 이 대응 자원 아홉에 서로 다른 ARN 을 주지 않았다 — 아래 단언이 의미를 잃는다."
  }

  assert {
    condition = output.app_task_resource_arns["auth"] == {
      profile_photo_bucket_arn = aws_s3_bucket.profile_photo.arn
    }
    error_message = "auth 의 profile_photo_bucket_arn 이 프로필 사진 버킷 ARN 이 아니다."
  }

  # segment 창고 = rewind 버킷(D4).
  assert {
    condition = output.app_task_resource_arns["clip"] == {
      broadcast_queue_arn = aws_sqs_queue.broadcast["clip"].arn
      render_queue_arn    = aws_sqs_queue.job["render"].arn
      render_dlq_arn      = aws_sqs_queue.job_dlq["render"].arn
      upload_queue_arn    = aws_sqs_queue.job["upload"].arn
      upload_dlq_arn      = aws_sqs_queue.job_dlq["upload"].arn
      thumbnail_queue_arn = aws_sqs_queue.job["thumbnail"].arn
      clips_bucket_arn    = aws_s3_bucket.clips.arn
      segment_bucket_arn  = aws_s3_bucket.rewind.arn
    }
    error_message = "clip 의 키 여덟이 대응 자원(계약9 clip 큐 · 일 큐 · DLQ · clips · rewind 버킷) ARN 이 아니다."
  }

  # 일꾼의 같은 이름 키는 clip 의 것과 같은 자원이다.
  assert {
    condition = alltrue(flatten([
      for worker in ["render", "upload"] : [
        for key, arn in output.app_task_resource_arns[worker] :
        arn == output.app_task_resource_arns["clip"][key]
      ]
    ]))
    error_message = "render · upload 의 키가 clip 의 같은 이름 키와 다른 ARN 을 가리킨다."
  }
}

# platform runtime_contract 의 ARN 꼴 검사와 같은 정규식이다 — 바꾸면 두 곳을
# 함께 고친다. mock ARN 은 임의 문자열이라 이 run 에서만 ARN 꼴 값을 준다.
# SQS 꼴은 계정 자리 12자리를 요구해 AWS 문서 예시 계정 값을 쓴다(platform
# apply_order 시험과 같은 값). 앞 run 이 만든 자원은 상태에 이미 있어 override
# 가 닿지 않으므로 상태를 따로 쓴다.
run "passes_platform_task_resource_arn_shape" {
  command   = apply
  state_key = "task_resource_arn_shape"

  override_resource {
    target = aws_sqs_queue.broadcast
    values = {
      arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo"
    }
  }

  override_resource {
    target = aws_sqs_queue.job
    values = {
      arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
    }
  }

  override_resource {
    target = aws_sqs_queue.job_dlq
    values = {
      arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render-dlq"
    }
  }

  override_resource {
    target = aws_s3_bucket.clips
    values = {
      arn = "arn:aws:s3:::pokeclip-prod-clips-apne2"
    }
  }

  override_resource {
    target = aws_s3_bucket.rewind
    values = {
      arn = "arn:aws:s3:::pokeclip-prod-rewind-apne2"
    }
  }

  override_resource {
    target = aws_s3_bucket.profile_photo
    values = {
      arn = "arn:aws:s3:::pokeclip-prod-profile-photos-apne2"
    }
  }

  assert {
    condition = alltrue(flatten([
      for arns in values(output.app_task_resource_arns) : [
        for key, arn in arns :
        endswith(key, "_bucket_arn") ? can(regex("^arn:aws:s3:::[a-z0-9][a-z0-9.-]+$", arn)) : can(regex("^arn:aws:sqs:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+(\\.fifo)?$", arn))
      ]
    ]))
    error_message = "app_task_resource_arns 의 값이 platform 의 와일드카드 없는 S3 버킷 · SQS 큐 ARN 꼴이 아니다."
  }
}
