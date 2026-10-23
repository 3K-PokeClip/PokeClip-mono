# 계약9 방송 생명주기 이벤트(POK-309 · 설계 D3 · D4 · D5) — SNS FIFO 주제 하나,
# 소비자(clip · chat)마다 SQS FIFO 큐 · 전용 DLQ · 재처리 허용 · raw 구독 ·
# 큐 정책, 큐 값 변수 기본값 넷, 출력 둘을 단언한다. ARN · URL 은 mock 이
# 만드는 가짜 값이다. override_data 가 network 원격 상태를 대신하므로 S3 를
# 읽지 않는다(AWS 호출 0).

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


# 중복 제거 좌표는 발행자가 주는 eventId 다(계약9 2절). 내용 기반 중복 제거를
# 켜면 본문이 다른 재시도가 멱등을 깨므로 주제 · 큐 모두 끈다(D3).
run "names_fifo_topic_without_content_dedup" {
  command = plan

  assert {
    condition     = aws_sns_topic.broadcast_lifecycle.name == "pokeclip-prod-broadcast-lifecycle.fifo"
    error_message = "주제 이름이 pokeclip-prod-broadcast-lifecycle.fifo 가 아니다."
  }

  assert {
    condition     = aws_sns_topic.broadcast_lifecycle.fifo_topic == true
    error_message = "주제가 FIFO 가 아니다."
  }

  assert {
    condition     = aws_sns_topic.broadcast_lifecycle.content_based_deduplication == false
    error_message = "주제의 content_based_deduplication 이 false 가 아니다(D3)."
  }

  # SNS 암호화는 발행자 KMS 권한이 미확인이라 끈다(D4 — PR 한계).
  assert {
    condition     = aws_sns_topic.broadcast_lifecycle.kms_master_key_id == null
    error_message = "주제에 KMS 키가 걸려 있다 — D4 는 SNS SSE 끔이다."
  }
}

# 단일 큐 공유 금지(계약9 1절) — 소비자마다 큐와 전용 DLQ 를 둔다.
run "names_fifo_queue_and_dlq_per_consumer" {
  command = plan

  assert {
    condition = {
      for consumer, queue in aws_sqs_queue.broadcast : consumer => queue.name
      } == {
      clip = "pokeclip-prod-broadcast-lifecycle-clip.fifo"
      chat = "pokeclip-prod-broadcast-lifecycle-chat.fifo"
    }
    error_message = "큐 이름이 pokeclip-prod-broadcast-lifecycle-{clip,chat}.fifo 둘이 아니다."
  }

  assert {
    condition = {
      for consumer, queue in aws_sqs_queue.broadcast_dlq : consumer => queue.name
      } == {
      clip = "pokeclip-prod-broadcast-lifecycle-clip-dlq.fifo"
      chat = "pokeclip-prod-broadcast-lifecycle-chat-dlq.fifo"
    }
    error_message = "DLQ 이름이 pokeclip-prod-broadcast-lifecycle-{clip,chat}-dlq.fifo 둘이 아니다."
  }

  assert {
    condition = alltrue([
      for queue in concat(values(aws_sqs_queue.broadcast), values(aws_sqs_queue.broadcast_dlq)) :
      queue.fifo_queue == true && queue.content_based_deduplication == false
    ])
    error_message = "큐 · DLQ 가운데 FIFO 가 아니거나 content_based_deduplication 이 켜진 것이 있다(D3)."
  }

  assert {
    condition = alltrue([
      for queue in concat(values(aws_sqs_queue.broadcast), values(aws_sqs_queue.broadcast_dlq)) :
      queue.sqs_managed_sse_enabled == true
    ])
    error_message = "큐 · DLQ 가운데 SSE-SQS 가 꺼진 것이 있다(D4)."
  }
}

# 값은 kty 답 3(dev 관례값)이고 3번 확인 전 임시값이다(D5).
run "defaults_queue_values_to_interim_contract9_values" {
  command = plan

  assert {
    condition = (
      var.broadcast_queue_visibility_timeout_seconds == 120 &&
      var.broadcast_queue_message_retention_seconds == 345600 &&
      var.broadcast_queue_max_receive_count == 3 &&
      var.broadcast_dlq_message_retention_seconds == 1209600
    )
    error_message = "큐 값 기본값이 숨김 120초 · 보관 4일 · 3회 실패 DLQ · DLQ 14일이 아니다."
  }

  assert {
    condition = alltrue([
      for queue in values(aws_sqs_queue.broadcast) :
      queue.visibility_timeout_seconds == 120 && queue.message_retention_seconds == 345600
    ])
    error_message = "큐의 숨김 시간 · 보관 기간이 120초 · 345600초가 아니다."
  }

  assert {
    condition = alltrue([
      for queue in values(aws_sqs_queue.broadcast_dlq) :
      queue.message_retention_seconds == 1209600
    ])
    error_message = "DLQ 보관 기간이 1209600초(14일)가 아니다."
  }
}

run "follows_queue_value_variables" {
  command = plan

  variables {
    broadcast_queue_visibility_timeout_seconds = 300
    broadcast_queue_message_retention_seconds  = 86400
    broadcast_dlq_message_retention_seconds    = 604800
  }

  assert {
    condition = alltrue([
      for queue in values(aws_sqs_queue.broadcast) :
      queue.visibility_timeout_seconds == 300 && queue.message_retention_seconds == 86400
    ])
    error_message = "큐의 숨김 시간 · 보관 기간이 변수를 따르지 않는다."
  }

  assert {
    condition = alltrue([
      for queue in values(aws_sqs_queue.broadcast_dlq) :
      queue.message_retention_seconds == 604800
    ])
    error_message = "DLQ 보관 기간이 변수를 따르지 않는다."
  }
}

# ARN · URL 은 계산 속성이다. 아래는 apply 로 돌려 mock 이 큐마다 다른 값을
# 만들게 한 뒤, 각 큐가 자기 짝을 가리키는지 본다.
run "redrives_each_queue_to_its_own_dlq" {
  command = apply

  assert {
    condition = length(distinct(concat(
      [for queue in values(aws_sqs_queue.broadcast) : queue.arn],
      [for queue in values(aws_sqs_queue.broadcast_dlq) : queue.arn],
    ))) == 4
    error_message = "mock 이 큐 · DLQ 넷에 서로 다른 ARN 을 주지 않았다 — 아래 단언이 의미를 잃는다."
  }

  assert {
    condition = alltrue([
      for consumer, queue in aws_sqs_queue.broadcast :
      jsondecode(queue.redrive_policy).deadLetterTargetArn == aws_sqs_queue.broadcast_dlq[consumer].arn
    ])
    error_message = "큐의 redrive_policy 가 같은 소비자의 DLQ ARN 을 가리키지 않는다."
  }

  assert {
    condition = alltrue([
      for queue in values(aws_sqs_queue.broadcast) :
      jsondecode(queue.redrive_policy).maxReceiveCount == 3
    ])
    error_message = "큐의 maxReceiveCount 가 3 이 아니다(D5)."
  }
}

# DLQ 는 자기 원 큐에서만 재처리를 받는다. 별도 자원이라 큐 ↔ DLQ 순환 참조가
# 없다(계획 28행).
run "allows_redrive_only_from_source_queue" {
  command = apply

  assert {
    condition = alltrue([
      for consumer, allow in aws_sqs_queue_redrive_allow_policy.broadcast_dlq :
      allow.queue_url == aws_sqs_queue.broadcast_dlq[consumer].url
    ])
    error_message = "재처리 허용 정책이 같은 소비자의 DLQ 에 걸려 있지 않다."
  }

  assert {
    condition = alltrue([
      for consumer, allow in aws_sqs_queue_redrive_allow_policy.broadcast_dlq :
      jsondecode(allow.redrive_allow_policy).redrivePermission == "byQueue" &&
      jsondecode(allow.redrive_allow_policy).sourceQueueArns == [aws_sqs_queue.broadcast[consumer].arn]
    ])
    error_message = "재처리 허용이 byQueue · 같은 소비자의 원 큐 하나가 아니다."
  }
}

# Raw Message Delivery — 소비자는 SNS 봉투 없이 계약9 본문을 받는다(계약9 1절).
run "subscribes_each_queue_with_raw_delivery" {
  command = apply

  assert {
    condition     = toset(keys(aws_sns_topic_subscription.broadcast)) == toset(["clip", "chat"])
    error_message = "구독이 clip · chat 둘이 아니다."
  }

  assert {
    condition = alltrue([
      for consumer, subscription in aws_sns_topic_subscription.broadcast :
      subscription.topic_arn == aws_sns_topic.broadcast_lifecycle.arn &&
      subscription.protocol == "sqs" &&
      subscription.endpoint == aws_sqs_queue.broadcast[consumer].arn
    ])
    error_message = "구독이 주제 → 같은 소비자의 큐(sqs)를 잇지 않는다."
  }

  assert {
    condition = alltrue([
      for subscription in values(aws_sns_topic_subscription.broadcast) :
      subscription.raw_message_delivery == true
    ])
    error_message = "구독의 raw_message_delivery 가 true 가 아니다."
  }
}

# 큐 정책은 한 문장 — 이 주제(aws:SourceArn)가 SNS 서비스로 보내는 SendMessage
# 만 허용한다. 수신 권한은 platform 이 자기 역할에 붙인다.
run "lets_only_broadcast_topic_send_to_queues" {
  command = apply

  assert {
    condition = alltrue([
      for consumer, policy in aws_sqs_queue_policy.broadcast :
      policy.queue_url == aws_sqs_queue.broadcast[consumer].url
    ])
    error_message = "큐 정책이 같은 소비자의 큐에 걸려 있지 않다."
  }

  assert {
    condition = alltrue([
      for policy in values(aws_sqs_queue_policy.broadcast) :
      length(jsondecode(policy.policy).Statement) == 1
    ])
    error_message = "큐 정책의 문장이 정확히 하나가 아니다."
  }

  assert {
    condition = alltrue([
      for consumer, policy in aws_sqs_queue_policy.broadcast : (
        jsondecode(policy.policy).Statement[0].Effect == "Allow" &&
        jsondecode(policy.policy).Statement[0].Principal == { Service = "sns.amazonaws.com" } &&
        jsondecode(policy.policy).Statement[0].Action == "sqs:SendMessage" &&
        jsondecode(policy.policy).Statement[0].Resource == aws_sqs_queue.broadcast[consumer].arn
      )
    ])
    error_message = "큐 정책 문장이 sns.amazonaws.com 의 sqs:SendMessage 허용(대상 = 같은 소비자의 큐)이 아니다."
  }

  assert {
    condition = alltrue([
      for policy in values(aws_sqs_queue_policy.broadcast) :
      jsondecode(policy.policy).Statement[0].Condition == {
        ArnEquals = { "aws:SourceArn" = aws_sns_topic.broadcast_lifecycle.arn }
      }
    ])
    error_message = "큐 정책 조건이 aws:SourceArn = 방송 주제 ARN 하나가 아니다."
  }
}

# 발행(sns:Publish)은 media 가, 수신은 platform 이 이 ARN 으로 붙인다(D5).
run "outputs_topic_and_queue_arns" {
  command = apply

  assert {
    condition     = output.broadcast_topic_arn == aws_sns_topic.broadcast_lifecycle.arn
    error_message = "출력 broadcast_topic_arn 이 주제 ARN 이 아니다."
  }

  assert {
    condition = output.broadcast_queue_arns == {
      clip = aws_sqs_queue.broadcast["clip"].arn
      chat = aws_sqs_queue.broadcast["chat"].arn
    }
    error_message = "출력 broadcast_queue_arns 가 소비자 → 원 큐 ARN(clip · chat)이 아니다."
  }
}
