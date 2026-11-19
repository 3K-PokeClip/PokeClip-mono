# 일꾼 render · upload 를 받는 SQS 큐 기준으로 0대 ↔ 1대 조절한다(POK-605).
# 일꾼마다 확장 대상 하나 · 정책 둘 · 경보 둘이다.
#
# 깨우기(wake): 그 일꾼 큐들의 대기(Visible) 합이 최신 1분에 0 보다 크면 원하는
# 수를 정확히 1로 둔다. 0대일 때 이 큐를 받는 주체가 없어 처리 중(NotVisible)은
# 신호가 아니라 넣지 않는다(계획 쟁점 2). 결측은 깨우지 않는다(notBreaching).
# FILL 은 최신 1분을 영구히 채워 경보를 걸 수 있어 쓰지 않는다.
#
# 재우기(idle): 같은 큐들의 대기 + 처리 중 합이 30분 내내 0 이면 원하는 수를
# 정확히 0으로 둔다. 처리 중인 태스크를 지키는 장치는 이 조건 하나뿐이다
# (일꾼은 처리 중 숨김을 연장해 메시지가 NotVisible 로 남는다). 결측은 유휴로
# 센다(breaching) — 잠든 큐는 지표를 보내지 않는다. 30 of 30(N-of-N)이라 같은
# 데이터포인트에서 깨우기와 동시에 ALARM 이 되지 않는다.
#
# 원하는 수의 실물 정본은 ECS · Application Auto Scaling 이다. 공용 모듈
# ecs_service 는 생성 때만 1을 넣고 그 뒤 desired_count 를 무시한다. 확장
# 대상의 정지 상태(suspended_state)는 긴급 정지 수단이라 Terraform 이 되살리지
# 않게 무시한다(README 「긴급 정지와 상태 어긋남」 · policy/guard 가 지킨다).
#
# 자원 주소는 철거 런북(runbooks/prod-teardown.md)의 삭제 허용 정규식이 기댄다
# — 이름 · 키를 바꾸면 그 정규식과 한 PR 로 고친다.

locals {
  # 일꾼 → 받는 큐의 작업 역할 대상 키(task_resource_keys 의 큐 ARN 키).
  scaling_queue_keys = {
    render = ["render_queue_arn", "thumbnail_queue_arn"]
    upload = ["upload_queue_arn"]
  }

  # 일꾼 → { 일 이름 → 큐 이름 }. 큐 이름은 큐 ARN 의 끝 조각이다(꼴은 계약
  # 검사 4). 키가 없으면 속성 오류가 아니라 계약 검사 문구로 멈추게 빈 값으로
  # 둔다.
  scaling_queues = {
    for key, arn_keys in local.scaling_queue_keys : key => {
      for arn_key in arn_keys :
      trimsuffix(arn_key, "_queue_arn") => try(regex("[^:]+$", lookup(local.task_resource_arns[key], arn_key, "")), "")
    }
  }

  scaling_service_names = {
    render = module.render.service_name
    upload = module.upload.service_name
  }
  scaling_cluster_name = regex("[^/]+$", local.platform.ecs_cluster_arn)

  queue_metric_period_seconds = 60
  queue_scaling_cooldown      = 60
  # 60초 × 30 = 30분(kty 결정 2026-11-19).
  queue_idle_periods = 30

  queue_visible_metric     = "ApproximateNumberOfMessagesVisible"
  queue_not_visible_metric = "ApproximateNumberOfMessagesNotVisible"

  # 경보 식에 넣을 지표. id 는 식 안 이름이라 소문자로 시작해야 한다.
  wake_queue_metrics = {
    for key, queues in local.scaling_queues : key => {
      for job, queue in queues : "visible_${job}" => { metric_name = local.queue_visible_metric, queue = queue }
    }
  }
  idle_queue_metrics = {
    for key, queues in local.scaling_queues : key => merge(
      local.wake_queue_metrics[key],
      {
        for job, queue in queues : "not_visible_${job}" => { metric_name = local.queue_not_visible_metric, queue = queue }
      },
    )
  }
}

resource "aws_appautoscaling_target" "worker" {
  for_each = local.scaling_service_names

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${local.scaling_cluster_name}/${each.value}"
  min_capacity       = 0
  max_capacity       = 1

  lifecycle {
    ignore_changes = [suspended_state]
  }

  depends_on = [terraform_data.runtime_contract]
}

resource "aws_appautoscaling_policy" "queue_wake" {
  for_each = aws_appautoscaling_target.worker

  name               = "${local.name_prefix}-${each.key}-queue-wake"
  policy_type        = "StepScaling"
  service_namespace  = each.value.service_namespace
  scalable_dimension = each.value.scalable_dimension
  resource_id        = each.value.resource_id

  step_scaling_policy_configuration {
    adjustment_type         = "ExactCapacity"
    cooldown                = local.queue_scaling_cooldown
    metric_aggregation_type = "Maximum"

    # 문턱 위 [0, +∞) — 대기가 하나라도 보이면 1대.
    step_adjustment {
      metric_interval_lower_bound = 0
      scaling_adjustment          = 1
    }
  }
}

resource "aws_appautoscaling_policy" "queue_idle" {
  for_each = aws_appautoscaling_target.worker

  name               = "${local.name_prefix}-${each.key}-queue-idle"
  policy_type        = "StepScaling"
  service_namespace  = each.value.service_namespace
  scalable_dimension = each.value.scalable_dimension
  resource_id        = each.value.resource_id

  step_scaling_policy_configuration {
    adjustment_type         = "ExactCapacity"
    cooldown                = local.queue_scaling_cooldown
    metric_aggregation_type = "Maximum"

    # 문턱 아래 (-∞, 0] — 하한을 비워 데이터 없이 ALARM 이 된 경보의 델타 0 도
    # 덮는다(평가 M-2).
    step_adjustment {
      metric_interval_upper_bound = 0
      scaling_adjustment          = 0
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "queue_wake" {
  for_each = local.wake_queue_metrics

  alarm_name          = aws_appautoscaling_policy.queue_wake[each.key].name
  alarm_description   = "Wake ${each.key} worker: visible messages on its SQS queues (POK-605)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_appautoscaling_policy.queue_wake[each.key].arn]

  metric_query {
    id          = "wake"
    expression  = join(" + ", sort(keys(each.value)))
    label       = "Visible messages"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = each.value

    content {
      id = metric_query.key

      metric {
        namespace   = "AWS/SQS"
        metric_name = metric_query.value.metric_name
        dimensions  = { QueueName = metric_query.value.queue }
        period      = local.queue_metric_period_seconds
        stat        = "Maximum"
      }
    }
  }

  depends_on = [terraform_data.runtime_contract]
}

resource "aws_cloudwatch_metric_alarm" "queue_idle" {
  for_each = local.idle_queue_metrics

  alarm_name          = aws_appautoscaling_policy.queue_idle[each.key].name
  alarm_description   = "Stop ${each.key} worker: no visible or in-flight messages on its SQS queues for 30 minutes (POK-605)."
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  evaluation_periods  = local.queue_idle_periods
  datapoints_to_alarm = local.queue_idle_periods
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_appautoscaling_policy.queue_idle[each.key].arn]

  metric_query {
    id          = "idle"
    expression  = join(" + ", sort(keys(each.value)))
    label       = "Visible and in-flight messages"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = each.value

    content {
      id = metric_query.key

      metric {
        namespace   = "AWS/SQS"
        metric_name = metric_query.value.metric_name
        dimensions  = { QueueName = metric_query.value.queue }
        period      = local.queue_metric_period_seconds
        stat        = "Maximum"
      }
    }
  }

  depends_on = [terraform_data.runtime_contract]
}
