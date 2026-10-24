# 계약9 방송 생명주기 이벤트(POK-309 · 설계 D3 · D4 · D5). 사이드카가 SNS FIFO
# 주제에 발행하고, 소비자(clip · chat-collector)마다 SQS FIFO 큐가 raw 로 받는다
# — 단일 큐 공유는 경쟁 소비라 금지다(계약9 1절). 발행 권한(sns:Publish)은
# media 루트, 수신 권한은 platform 루트가 자기 역할에 붙인다(CONTRACT-runtime.md
# 「출력과 소유 경계」).
#
# 중복 제거: 좌표는 발행자가 주는 MessageDeduplicationId = eventId 다(D3).
# 명시 ID 는 내용 기반 중복 제거보다 우선한다. 그래서 주제 · 큐에서 내용 기반
# 중복 제거를 끈 실제 효과는 하나다 — ID 를 빠뜨린 발행이 본문 해시로 조용히
# 중복 제거되지 않고 실패한다.
#
# 암호화: 큐는 SSE-SQS. 주제는 끈다 — 발행자 KMS 권한이 미확인이다(D4 · PR
# 한계). 그래서 편지는 주제에 머무는 동안 서버 측 암호화가 없고, 큐에 들어간
# 뒤부터 SSE-SQS 로 암호화된다.
#
# 주제 정책: SNS 가 주제에 붙이는 기본 정책(Principal * · AWS:SourceOwner =
# 이 계정) 대신 명시 정책을 둔다(kty 2026-10-24). 기본 정책은 모호하다 — *
# 주체가 조건 하나에 기대고, 그 조건 키는 일부 서비스만 채우며 AWS 가
# 폐지 예정으로 적었다. 리스 계정에는 dev 와 prod 주체가 같이 있어, 주제
# 정책은 계정 루트에만 열고 누가 쓰는지는 신원 정책이 정한다. 사이드카
# 발행 권한(sns:Publish)은 media 역할의 신원 정책이다.

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

# 실행기의 data.aws_partition.current 는 켜졌을 때만 읽어(count) 따로 둔다.
data "aws_partition" "broadcast" {}

data "aws_caller_identity" "current" {}

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

# 계정 루트 주체 = 이 계정의 IAM 신원 정책이 허용한 주체만. 동작은 기본
# 정책의 여덟이다(provider 기본 정책의 SNS:Receive 는 SNS 정책 동작이 아니라
# 뺀다). 같은 계정 주체는 신원 정책만으로 접근하므로 이 정책이 교체되는
# 동안에도 발행 · 구독이 끊기지 않는다 — 구독과 순서를 두지 않는다.
#
# 조건(aws:PrincipalArn = prod 역할): 1단-다 직전 점검 2-3 의 정책
# 시뮬레이터는 계정 루트 문장을 같은 계정 호출자 전부에 맞춰, 리스 계정의
# dev 주체도 이 주제에 ALLOWED 로 판정했다. 실제 AWS 는 계정 루트 문장을
# 신원 정책에 위임하므로 동작은 바뀌지 않는다 — 발행은 media 역할, 관리
# (Terraform)는 kty IAM 사용자의 신원 정책이 연다.
resource "aws_sns_topic_policy" "broadcast_lifecycle" {
  arn = aws_sns_topic.broadcast_lifecycle.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowAccountIdentityPolicies"
        Effect = "Allow"
        Principal = {
          AWS = "arn:${data.aws_partition.broadcast.partition}:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action = [
          "SNS:GetTopicAttributes",
          "SNS:SetTopicAttributes",
          "SNS:AddPermission",
          "SNS:RemovePermission",
          "SNS:DeleteTopic",
          "SNS:Subscribe",
          "SNS:ListSubscriptionsByTopic",
          "SNS:Publish",
        ]
        Resource = aws_sns_topic.broadcast_lifecycle.arn
        Condition = {
          ArnLike = {
            "aws:PrincipalArn" = "arn:${data.aws_partition.broadcast.partition}:iam::${data.aws_caller_identity.current.account_id}:role/pokeclip-prod-*"
          }
        }
      },
    ]
  })
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

# chat-collector(POK-306)가 아직 platform 서비스가 아니라 chat 큐의 수신
# 정책 대상은 이 출력에 남는다. clip 은 app_task_resource_arns 로 받는다.
output "broadcast_queue_arns" {
  description = "소비자(clip · chat) → 계약9 SQS FIFO 큐 ARN. chat-collector(POK-306) 수신 정책 대상이고, clip 은 app_task_resource_arns 를 쓴다. 큐 URL 은 app_env 에 있다."
  value       = { for consumer, queue in aws_sqs_queue.broadcast : consumer => queue.arn }
}
