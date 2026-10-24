# 일 큐 셋과 DLQ(POK-408 · 설계 D1 · D2 · K3) — 표준 SQS 여섯(render ·
# thumbnail · upload 의 원 큐와 전용 DLQ), 이름 · 숨김 · 보관 · 받기 대기 ·
# SSE-SQS, redrive 짝과 maxReceiveCount 3, 재처리 허용 byQueue 를 단언한다.
# 큐 정책은 두지 않는다(같은 계정 신원 정책만 — D2). 없는 자원은 시험이
# 참조할 수 없어 aws_sqs_queue_policy 가 broadcast 하나뿐임은 커밋 전 grep 으로
# 확인한다. ARN · URL 은 mock 이 만드는 가짜 값이다. override_data 가 network
# 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

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

# clip 이 MessageGroupId 없이 보낸다(RenderQueueClient.java:30 등) — FIFO 큐면
# 그 요청이 거부된다(D1). 이름은 dev 의 pokeclip-jobs-* 에 prod 접두다(D2).
run "names_standard_queue_and_dlq_per_job" {
  command = plan

  assert {
    condition = {
      for job, queue in aws_sqs_queue.job : job => queue.name
      } == {
      render    = "pokeclip-prod-jobs-render"
      thumbnail = "pokeclip-prod-jobs-thumbnail"
      upload    = "pokeclip-prod-jobs-upload"
    }
    error_message = "원 큐 이름이 pokeclip-prod-jobs-{render,thumbnail,upload} 셋이 아니다."
  }

  assert {
    condition = {
      for job, queue in aws_sqs_queue.job_dlq : job => queue.name
      } == {
      render    = "pokeclip-prod-jobs-render-dlq"
      thumbnail = "pokeclip-prod-jobs-thumbnail-dlq"
      upload    = "pokeclip-prod-jobs-upload-dlq"
    }
    error_message = "DLQ 이름이 pokeclip-prod-jobs-{render,thumbnail,upload}-dlq 셋이 아니다(썸네일 DLQ 포함 — K3)."
  }

  assert {
    condition = alltrue([
      for queue in concat(values(aws_sqs_queue.job), values(aws_sqs_queue.job_dlq)) :
      queue.fifo_queue == false
    ])
    error_message = "일 큐 · DLQ 가운데 FIFO 가 있다 — clip 은 MessageGroupId 없이 보낸다(D1)."
  }

  assert {
    condition = alltrue([
      for queue in concat(values(aws_sqs_queue.job), values(aws_sqs_queue.job_dlq)) :
      queue.sqs_managed_sse_enabled == true
    ])
    error_message = "일 큐 · DLQ 가운데 SSE-SQS 가 꺼진 것이 있다(D2)."
  }

  # 일꾼 · clip 이 롱폴링 대기를 요청에 싣는다 — 큐 기본 대기는 0 이다(D2).
  assert {
    condition = alltrue([
      for queue in concat(values(aws_sqs_queue.job), values(aws_sqs_queue.job_dlq)) :
      queue.receive_wait_time_seconds == 0
    ])
    error_message = "일 큐 · DLQ 가운데 receive_wait_time_seconds 가 0 이 아닌 것이 있다(D2)."
  }
}

# 숨김은 일꾼 yml 기본값과 같아야 한다(render 600 · thumbnail 120 · upload
# 900 — 3번 코드와 짝). 보관은 원 큐 4일 · DLQ 14일이다(D2).
run "sets_visibility_and_retention_from_job_constants" {
  command = plan

  assert {
    condition = {
      for job, queue in aws_sqs_queue.job : job => queue.visibility_timeout_seconds
      } == {
      render    = 600
      thumbnail = 120
      upload    = 900
    }
    error_message = "원 큐 숨김 시간이 render 600 · thumbnail 120 · upload 900 이 아니다(일꾼 yml 기본값과 짝)."
  }

  assert {
    condition = alltrue([
      for queue in values(aws_sqs_queue.job) :
      queue.message_retention_seconds == 345600
    ])
    error_message = "원 큐 보관 기간이 345600초(4일)가 아니다."
  }

  assert {
    condition = alltrue([
      for queue in values(aws_sqs_queue.job_dlq) :
      queue.message_retention_seconds == 1209600
    ])
    error_message = "DLQ 보관 기간이 1209600초(14일)가 아니다."
  }
}

# ARN · URL 은 계산 속성이다. apply 로 돌려 mock 이 큐마다 다른 값을 만들게
# 한 뒤, 각 원 큐가 자기 DLQ 를 가리키는지 본다.
run "redrives_each_job_queue_to_its_own_dlq" {
  command = apply

  assert {
    condition = length(distinct(concat(
      [for queue in values(aws_sqs_queue.job) : queue.arn],
      [for queue in values(aws_sqs_queue.job_dlq) : queue.arn],
    ))) == 6
    error_message = "mock 이 일 큐 · DLQ 여섯에 서로 다른 ARN 을 주지 않았다 — 아래 단언이 의미를 잃는다."
  }

  assert {
    condition = alltrue([
      for job, queue in aws_sqs_queue.job :
      jsondecode(queue.redrive_policy).deadLetterTargetArn == aws_sqs_queue.job_dlq[job].arn
    ])
    error_message = "원 큐의 redrive_policy 가 같은 일의 DLQ ARN 을 가리키지 않는다."
  }

  # 세 번 실패하면 DLQ 로 간다(render README 「세 번이면 DLQ」 · clip max-attempts 3).
  assert {
    condition = alltrue([
      for queue in values(aws_sqs_queue.job) :
      jsondecode(queue.redrive_policy).maxReceiveCount == 3
    ])
    error_message = "원 큐의 maxReceiveCount 가 3 이 아니다(D2)."
  }
}

# DLQ 는 자기 원 큐에서만 재처리를 받는다. 별도 자원이라 원 큐 ↔ DLQ 순환
# 참조가 없다(방송 큐와 같은 꼴).
run "allows_job_redrive_only_from_source_queue" {
  command = apply

  assert {
    condition     = toset(keys(aws_sqs_queue_redrive_allow_policy.job_dlq)) == toset(["render", "thumbnail", "upload"])
    error_message = "재처리 허용 정책이 render · thumbnail · upload DLQ 셋이 아니다."
  }

  assert {
    condition = alltrue([
      for job, allow in aws_sqs_queue_redrive_allow_policy.job_dlq :
      allow.queue_url == aws_sqs_queue.job_dlq[job].url
    ])
    error_message = "재처리 허용 정책이 같은 일의 DLQ 에 걸려 있지 않다."
  }

  assert {
    condition = alltrue([
      for job, allow in aws_sqs_queue_redrive_allow_policy.job_dlq :
      jsondecode(allow.redrive_allow_policy).redrivePermission == "byQueue" &&
      jsondecode(allow.redrive_allow_policy).sourceQueueArns == [aws_sqs_queue.job[job].arn]
    ])
    error_message = "재처리 허용이 byQueue · 같은 일의 원 큐 하나가 아니다."
  }
}
