# /dvr 하한 경보(POK-525 K5 · 계획 Ⓓ-3) — dvr-floor 가 막지 않고 넘긴 이상
# 상태를 수로 드러낸다. 함수는 KVS 읽기 예외 · 하한 값 이상을 막지 않고
# 넘기며(fail-open, K5 (가)), 그때 고정 로그 한 줄을 낸다. 그 줄을 지표 필터로
# 세어 경보를 울리고, 함수가 잡지 못한 예외(→ /dvr 503)는 CloudFront 기본 지표
# FunctionExecutionErrors 로 본다(K-D5 (a)).
#
# 리전: 함수 로그는 어느 엣지에서 돌든 us-east-1 로그 그룹에 쌓이고(function_logs.tf
# 머리 주석), CloudFront 기본 지표도 us-east-1 이다(차원 Region = Global — AWS
# 개발자 안내서 programming-cloudwatch-metrics · 2026-11-11 확인). 경보는
# 지표와 같은 리전이어야 하고 알림 주제도 경보와 같은 리전이라, 자원 모두
# us-east-1 이다. function_logs.tf 처럼 자원에 region 을 적어 기본 provider 의
# 리전이 바뀌어도 따라가지 않게 한다.
#
# 지표 필터(CloudWatch Logs 「Filter pattern syntax」 · 「Creating metrics from
# log events」 — 2026-11-11 확인): 패턴은 대소문자를 가리고, 영숫자 밖 글자
# (공백)가 든 구절은 따옴표로 감싼다. 필터는 만든 뒤의 로그만 세고(소급 없음),
# Standard 로그 클래스 그룹에만 걸린다 — 함수 로그 그룹은 class 를 적지 않아
# 기본(STANDARD)이다. 함수 로그 전달은 best-effort 라(드물게 늦거나 빠짐) 경보
# 수는 완전한 수가 아니다.
#
# 알림(K-D1 (가)): 주제는 여기서 만들고 이메일 구독은 저장소 밖(kty 가 CLI 로
# 한 번 — README 「경보」)이다. 공개 저장소에 주소를 두지 않고, 확인 대기
# 구독은 provider 가 지우지 못해 상태에 넣지 않는다. 주제는 암호화하지 않는다
# (prod/runtime 의 broadcast_events.tf 와 같음). 실리는 것은 경보 알림 본문
# (경보 이름 · 설명 · 계정 ID · 경보 ARN · 상태와 상태 이유 · 지표 설정 —
# CloudWatch 사용자 안내서 「Notifying users on alarm changes」 의 알림 스키마 ·
# 2026-11-11 확인)이라 비밀 · 사용자 데이터가 없다. 고객 관리 키(CMK)로
# 암호화하면 키 정책에 CloudWatch 를 따로 열어야 한다.

locals {
  # K-D4(kty 2026-11-11) — 5분 합이 1 이상이면 한 번에 울린다. 데이터 없음 =
  # 정상(로그가 없으면 지표 점이 없다). 실행 오류 경보도 같은 문턱이다.
  dvr_floor_alarm_period_seconds = 300
  dvr_floor_alarm_threshold      = 1

  # 지표 필터로 세는 함수 로그 표지 — 키는 module.media 출력
  # dvr_floor_log_markers 의 키, 값은 PokeClip/Edge 의 지표 이름이다. 표지
  # 글자는 그 출력(dvr-floor.js 상수)에서만 온다(E43 — 한 출처).
  dvr_floor_log_metrics = {
    value_invalid = "DvrFloorValueInvalid"
    kvs_error     = "DvrFloorKvsError"
  }
  dvr_floor_metric_namespace = "PokeClip/Edge"

  dvr_floor_log_alarm_descriptions = {
    value_invalid = "dvr-floor passed a /dvr segment because the round floor value in KVS is not a valid floor (DVR FLOOR VALUE INVALID)."
    kvs_error     = "dvr-floor passed a /dvr segment because reading the round floor from KVS failed (DVR FLOOR KVS ERROR)."
  }
  dvr_floor_best_effort_note = "Function logs are delivered best-effort, so the count is not complete. See runbooks/edge-tests.md (POK-525)."
}

resource "aws_sns_topic" "edge_alarms" {
  region = "us-east-1"
  name   = "pokeclip-prod-edge-alarms"
}

# SNS 기본 정책(Principal * · AWS:SourceOwner = 이 계정) 대신 명시 정책을
# 둔다 — prod/runtime broadcast_events.tf 의 주제 정책과 같은 까닭이다(리스
# 계정에 dev 와 prod 주체가 같이 있어, 기본 정책은 같은 계정 dev 주체에도
# 발행 · 구독 · 삭제를 연다). 계정 루트에는 읽기 하나만 열고, 관리 · 구독은
# 신원 정책(kty IAM 사용자)이 정한다. 발행은 이 루트의 경보 셋이 보내는
# CloudWatch 뿐이다 — 경보 ARN(aws:SourceArn) · 계정(aws:SourceAccount)으로
# 좁힌다(CloudWatch 사용자 안내서 「Notifying users on alarm changes」 의
# 혼동된 대리인 방지 예 — 2026-11-11 확인). 기본 정책을 지우면 이 문장 없이는
# 경보가 주제에 발행하지 못한다.
resource "aws_sns_topic_policy" "edge_alarms" {
  region = "us-east-1"
  arn    = aws_sns_topic.edge_alarms.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowAccountReadTopicAttributes"
        Effect = "Allow"
        Principal = {
          AWS = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"
        }
        Action   = ["SNS:GetTopicAttributes"]
        Resource = aws_sns_topic.edge_alarms.arn
      },
      {
        Sid       = "AllowEdgeAlarmsPublish"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "SNS:Publish"
        Resource  = aws_sns_topic.edge_alarms.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = sort(concat(
              [
                for alarm in aws_cloudwatch_metric_alarm.dvr_floor_log :
                alarm.arn
              ],
              [aws_cloudwatch_metric_alarm.dvr_floor_execution_errors.arn],
            ))
          }
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      },
    ]
  })
}

resource "aws_cloudwatch_log_metric_filter" "dvr_floor" {
  for_each = local.dvr_floor_log_metrics

  region         = "us-east-1"
  name           = "pokeclip-prod-dvr-floor-${replace(each.key, "_", "-")}"
  log_group_name = aws_cloudwatch_log_group.cloudfront_function["dvr_floor"].name
  pattern        = "\"${module.media.dvr_floor_log_markers[each.key]}\""

  metric_transformation {
    name      = each.value
    namespace = local.dvr_floor_metric_namespace
    value     = "1"
  }
}

# 로그 지표 경보 둘(VALUE INVALID · KVS ERROR) — 지표는 위 필터의 것이다.
resource "aws_cloudwatch_metric_alarm" "dvr_floor_log" {
  for_each = aws_cloudwatch_log_metric_filter.dvr_floor

  region              = "us-east-1"
  alarm_name          = each.value.name
  namespace           = each.value.metric_transformation[0].namespace
  metric_name         = each.value.metric_transformation[0].name
  statistic           = "Sum"
  period              = local.dvr_floor_alarm_period_seconds
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  threshold           = local.dvr_floor_alarm_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.edge_alarms.arn]

  alarm_description = join(" ", [
    local.dvr_floor_log_alarm_descriptions[each.key],
    local.dvr_floor_best_effort_note,
  ])
}

# 함수가 잡지 못한 예외(K-D5 (a)) — dvr-floor 는 최상위 catch 를 두지 않아 그
# 요청은 /dvr 503 이다. CloudFront 기본 지표라 지표 필터가 없다.
resource "aws_cloudwatch_metric_alarm" "dvr_floor_execution_errors" {
  region              = "us-east-1"
  alarm_name          = "pokeclip-prod-dvr-floor-execution-errors"
  alarm_description   = "dvr-floor failed to complete (CloudFront FunctionExecutionErrors) and the /dvr request got a 503."
  namespace           = "AWS/CloudFront"
  metric_name         = "FunctionExecutionErrors"
  statistic           = "Sum"
  period              = local.dvr_floor_alarm_period_seconds
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  threshold           = local.dvr_floor_alarm_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.edge_alarms.arn]

  dimensions = {
    FunctionName = module.media.function_names.dvr_floor
    Region       = "Global"
  }
}

output "edge_alarm_topic_arn" {
  description = "/dvr 하한 경보 셋의 알림 주제 ARN(us-east-1) — kty 가 aws sns subscribe 로 이메일 구독을 단다(README 「경보」)."
  value       = aws_sns_topic.edge_alarms.arn
}
