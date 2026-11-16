# clip 치우개 종료 경보(POK-588) — 치우개(StaleBroadcastReaper)가 방송을 닫을
# 때마다 clip 이 남기는 broadcast.reaper.ended 줄 하나를 지표 필터로 세어, 5분
# 합이 1 이상이면 알린다. 한 줄이 방송 하나를 닫은 것이다. 울리면 그 방송이
# 정말 끝났는지 본다(README 「clip 치우개 오판 감지 · 복구」). 선례는 prod/edge
# 의 /dvr 하한 경보(dvr_floor_alarms.tf)이고, 지표 필터 · 알림 주제의 규칙
# (패턴 문법 · 소급 없음 · 구독은 저장소 밖 · 주제 암호화 없음)도 그 머리
# 주석과 같다.
#
# 경보는 치우개 스위치(clip_broadcast_reaper_enabled)와 상관없이 늘 만든다 —
# 꺼져 있으면 그 줄이 없어 울리지 않는다.
#
# 로그 형식: clip 은 Spring Boot 기본 콘솔 형식(평문 — application.yml 의
# logging.pattern 은 레벨 자리만 바꾼다)이라 JSON 필터가 아니라 따옴표 구절로
# 센다. 구절은 로그 문자열 앞머리 「broadcast.reaper.ended streamId=」 다 —
# streamId= 까지 넣어 broadcast.reaper.ended_listener_failed 줄은 세지 않는다.
#
# 리전: clip 로그 그룹(modules/ecs_service)이 provider 기본 리전(서울)에 있고,
# 지표 · 경보 · 알림 주제도 같은 리전이어야 해 region 을 따로 적지 않는다.

locals {
  # 5분 합이 1 이상이면 한 번에 울린다(/dvr 하한 경보와 같은 문턱). 데이터
  # 없음 = 정상(로그가 없으면 지표 점이 없다).
  reaper_alarm_period_seconds = 300
  reaper_alarm_threshold      = 1

  reaper_ended_log_marker = "broadcast.reaper.ended streamId="
}

resource "aws_sns_topic" "platform_alarms" {
  name = "${local.name_prefix}-platform-alarms"
}

# SNS 기본 정책(Principal * · AWS:SourceOwner = 이 계정) 대신 명시 정책을
# 둔다 — prod/edge dvr_floor_alarms.tf 의 주제 정책과 같은 까닭이다(리스 계정에
# dev 와 prod 주체가 같이 있어, 기본 정책은 같은 계정 dev 주체에도 발행 ·
# 구독 · 삭제를 연다). 계정 루트에는 읽기 하나만 열고, 관리 · 구독은 신원
# 정책(kty IAM 사용자)이 정한다. 이 자원 정책이 여는 발행은 이 루트의 경보가
# 보내는 CloudWatch 뿐이다 — 경보 ARN(aws:SourceArn) · 계정
# (aws:SourceAccount)으로 좁힌다. 같은 계정 주체가 신원 권한으로 하는 발행 ·
# 주제 삭제는 이 정책이 막지 않고, 직전 점검(principal_sim 의
# platform-alarm-topic 행)이 본다.
resource "aws_sns_topic_policy" "platform_alarms" {
  arn = aws_sns_topic.platform_alarms.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowAccountReadTopicAttributes"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${local.account_id}:root" }
        Action    = ["SNS:GetTopicAttributes"]
        Resource  = aws_sns_topic.platform_alarms.arn
      },
      {
        Sid       = "AllowPlatformAlarmsPublish"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "SNS:Publish"
        Resource  = aws_sns_topic.platform_alarms.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = [aws_cloudwatch_metric_alarm.clip_reaper_ended.arn]
          }
          StringEquals = {
            "aws:SourceAccount" = local.account_id
          }
        }
      },
    ]
  })
}

resource "aws_cloudwatch_log_metric_filter" "clip_reaper_ended" {
  name           = "${local.name_prefix}-clip-reaper-ended"
  log_group_name = module.clip.log_group_name
  pattern        = "\"${local.reaper_ended_log_marker}\""

  metric_transformation {
    name      = "ClipReaperEnded"
    namespace = "PokeClip/Platform"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "clip_reaper_ended" {
  alarm_name          = aws_cloudwatch_log_metric_filter.clip_reaper_ended.name
  alarm_description   = "clip StaleBroadcastReaper ended a live broadcast whose end letter never arrived (broadcast.reaper.ended). Check that the broadcast really ended - prod/platform README, clip reaper false-close section (POK-588)."
  namespace           = aws_cloudwatch_log_metric_filter.clip_reaper_ended.metric_transformation[0].namespace
  metric_name         = aws_cloudwatch_log_metric_filter.clip_reaper_ended.metric_transformation[0].name
  statistic           = "Sum"
  period              = local.reaper_alarm_period_seconds
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  threshold           = local.reaper_alarm_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.platform_alarms.arn]
}

output "platform_alarm_topic_arn" {
  description = "clip 치우개 종료 경보의 알림 주제 ARN — kty 가 aws sns subscribe 로 이메일 구독을 단다(README 「clip 치우개 경보」)."
  value       = aws_sns_topic.platform_alarms.arn
}
