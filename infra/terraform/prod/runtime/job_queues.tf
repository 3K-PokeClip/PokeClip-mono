# 일 큐 셋과 DLQ(POK-408 · 설계 D1 · D2 · K3) — clip 이 render · thumbnail ·
# upload 주문을 보내고 일꾼이 받는 표준 SQS 큐와 일마다 전용 DLQ 다.
#
# 3번 코드와 짝 — 한쪽만 바꾸면 깨진다:
#   - 표준 큐(FIFO 아님): clip 이 MessageGroupId 없이 보낸다
#     (services/clip RenderQueueClient.java:30 · UploadQueueClient.java:29 ·
#     ThumbnailQueueClient.java:18). FIFO 큐면 그 요청이 거부된다.
#   - 숨김 시간: 일꾼 yml 기본값과 같다(workers/render application.yml 24 · 34,
#     workers/upload application.yml 30). 일꾼에 주는 숨김 env 도 이 상수에서
#     만든다(app_env.tf — 숨김을 변수로 두지 않는 이유는 README 「대안」).
#   - maxReceiveCount 3: 일꾼 README 「세 번이면 DLQ」 · clip max-attempts 3.
#
# 썸네일 DLQ 도 둔다(K3) — redrive 가 없으면 실패한 주문이 보관 4일 동안
# 계속 돈다. clip 은 썸네일 DLQ 를 읽지 않아 출력 키는 내지 않는다.
#
# 두지 않는 것:
#   - 큐 정책: 보내고 받는 주체가 모두 같은 계정의 작업 역할이라 신원 정책만
#     쓴다(platform · 배치 루트).
#   - 출력: 큐 주소는 app_env, ARN 은 app_task_resource_arns 가 모은다(D10).

locals {
  job_queue_name_prefix = "pokeclip-prod-jobs"

  # 일 → 숨김 시간(초). 일꾼 yml 기본값과 짝이다(머리 주석).
  job_queue_visibility_timeout_seconds = {
    render    = 600
    thumbnail = 120
    upload    = 900
  }

  job_queue_max_receive_count         = 3
  job_queue_message_retention_seconds = 345600  # 4일
  job_dlq_message_retention_seconds   = 1209600 # 14일 — clip 정리기가 1분마다 읽는다
}

resource "aws_sqs_queue" "job_dlq" {
  for_each = local.job_queue_visibility_timeout_seconds

  name                      = "${local.job_queue_name_prefix}-${each.key}-dlq"
  fifo_queue                = false
  message_retention_seconds = local.job_dlq_message_retention_seconds
  receive_wait_time_seconds = 0
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "job" {
  for_each = local.job_queue_visibility_timeout_seconds

  name                       = "${local.job_queue_name_prefix}-${each.key}"
  fifo_queue                 = false
  visibility_timeout_seconds = each.value
  message_retention_seconds  = local.job_queue_message_retention_seconds
  receive_wait_time_seconds  = 0
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.job_dlq[each.key].arn
    maxReceiveCount     = local.job_queue_max_receive_count
  })
}

# DLQ 는 자기 원 큐에서만 재처리를 받는다. DLQ 자원 안에 두면 원 큐 ↔ DLQ 가
# 서로를 참조해 순환이 생겨 별도 자원으로 둔다(방송 큐와 같은 꼴).
resource "aws_sqs_queue_redrive_allow_policy" "job_dlq" {
  for_each = local.job_queue_visibility_timeout_seconds

  queue_url = aws_sqs_queue.job_dlq[each.key].url
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.job[each.key].arn]
  })
}
