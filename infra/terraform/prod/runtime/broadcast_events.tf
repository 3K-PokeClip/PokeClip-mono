# 계약9 방송 생명주기 이벤트(POK-309 · 설계 D3 · D4 · D5). 사이드카가 SNS FIFO
# 주제에 발행하고, 소비자(clip · chat-collector)마다 SQS FIFO 큐가 raw 로 받는다
# — 단일 큐 공유는 경쟁 소비라 금지다(계약9 1절). 발행 권한(sns:Publish)은
# media 루트, 수신 권한은 platform 루트가 자기 역할에 붙인다(CONTRACT-runtime.md
# 「출력과 소유 경계」).
#
# 중복 제거: 주제 · 큐 모두 내용 기반 중복 제거를 끈다. 발행자가
# MessageDeduplicationId = eventId 를 준다 — 빠뜨리면 발행이 시끄럽게 실패한다.
# 켜면 본문이 다른 재시도가 멱등을 깬다(D3).
#
# 암호화: 큐는 SSE-SQS. 주제는 끈다 — 발행자 KMS 권한이 미확인이다(D4 · PR
# 한계).

variable "broadcast_queue_visibility_timeout_seconds" {
  description = "계약9 큐의 숨김 시간(초). 3번 확인 전 임시값(dev 관례값)."
  type        = number
  default     = 120
}

variable "broadcast_queue_message_retention_seconds" {
  description = "계약9 큐의 보관 기간(초 — 4일). 3번 확인 전 임시값(dev 관례값)."
  type        = number
  default     = 345600
}

variable "broadcast_queue_max_receive_count" {
  description = "계약9 큐에서 DLQ 로 옮기기 전 받기 횟수. 3번 확인 전 임시값(dev 관례값)."
  type        = number
  default     = 3
}

variable "broadcast_dlq_message_retention_seconds" {
  description = "계약9 DLQ 의 보관 기간(초 — 14일). 3번 확인 전 임시값(dev 관례값)."
  type        = number
  default     = 1209600
}

locals {
  broadcast_name_prefix = "pokeclip-prod-broadcast-lifecycle"

  # 소비자 → 큐 이름 꼬리. chat 은 chat-collector 다(app_env.tf).
  broadcast_consumers = toset(["clip", "chat"])
}

resource "aws_sns_topic" "broadcast_lifecycle" {
  name                        = "${local.broadcast_name_prefix}.fifo"
  fifo_topic                  = true
  content_based_deduplication = false
}

resource "aws_sqs_queue" "broadcast_dlq" {
  for_each = local.broadcast_consumers

  name                        = "${local.broadcast_name_prefix}-${each.key}-dlq.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  message_retention_seconds   = var.broadcast_dlq_message_retention_seconds
  sqs_managed_sse_enabled     = true
}

resource "aws_sqs_queue" "broadcast" {
  for_each = local.broadcast_consumers

  name                        = "${local.broadcast_name_prefix}-${each.key}.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  visibility_timeout_seconds  = var.broadcast_queue_visibility_timeout_seconds
  message_retention_seconds   = var.broadcast_queue_message_retention_seconds
  sqs_managed_sse_enabled     = true

  # 처리 예외만 전용 DLQ 로 간다 — 서로의 소비 실패에 영향받지 않는다(계약9 3절).
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.broadcast_dlq[each.key].arn
    maxReceiveCount     = var.broadcast_queue_max_receive_count
  })
}

# DLQ 는 자기 원 큐에서만 재처리를 받는다. DLQ 자원 안에 두면 원 큐 ↔ DLQ 가
# 서로를 참조해 순환이 생겨 별도 자원으로 둔다.
resource "aws_sqs_queue_redrive_allow_policy" "broadcast_dlq" {
  for_each = local.broadcast_consumers

  queue_url = aws_sqs_queue.broadcast_dlq[each.key].url
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.broadcast[each.key].arn]
  })
}

# 이 주제가 보내는 SendMessage 만 허용한다(혼동된 대리인 방지 — aws:SourceArn).
resource "aws_sqs_queue_policy" "broadcast" {
  for_each = local.broadcast_consumers

  queue_url = aws_sqs_queue.broadcast[each.key].url
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowBroadcastTopicSendMessage"
        Effect    = "Allow"
        Principal = { Service = "sns.amazonaws.com" }
        Action    = "sqs:SendMessage"
        Resource  = aws_sqs_queue.broadcast[each.key].arn
        Condition = {
          ArnEquals = { "aws:SourceArn" = aws_sns_topic.broadcast_lifecycle.arn }
        }
      },
    ]
  })
}

# Raw Message Delivery — 소비자는 SNS 봉투 없이 계약9 본문을 받는다.
#
# 구독은 큐 정책 뒤에 만든다. 발행 중인 주제에 큐를 다시 만들 때 정책보다
# 구독이 먼저 생기면 그 사이 편지는 권한 오류로 버려진다(SNS 는 권한 오류
# 전달을 재시도하지 않는다).
resource "aws_sns_topic_subscription" "broadcast" {
  for_each = local.broadcast_consumers

  topic_arn            = aws_sns_topic.broadcast_lifecycle.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.broadcast[each.key].arn
  raw_message_delivery = true

  depends_on = [aws_sqs_queue_policy.broadcast]
}

output "broadcast_topic_arn" {
  description = "계약9 SNS FIFO 주제 ARN — media 사이드카 역할의 sns:Publish 대상."
  value       = aws_sns_topic.broadcast_lifecycle.arn
}

output "broadcast_queue_arns" {
  description = "소비자(clip · chat) → 계약9 SQS FIFO 큐 ARN — platform 의 수신 정책 대상. 큐 URL 은 app_env 에 있다."
  value       = { for consumer, queue in aws_sqs_queue.broadcast : consumer => queue.arn }
}
