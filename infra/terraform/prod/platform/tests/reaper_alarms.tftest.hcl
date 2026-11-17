# prod/platform 의 clip 치우개 종료 경보(POK-588)를 mock provider 로 단언한다 —
# clip 로그 그룹의 broadcast.reaper.ended 줄을 세는 지표 필터 · 5분 합 1 이상에
# 울리는 경보 · 경보만 발행하는 알림 주제와 그 명시 정책. 선례는 prod/edge 의
# /dvr 하한 경보(dvr_floor_alarms.tf · edge.tftest.hcl)다.
#
# 앞 루트 상태 흉내 · 가짜 값은 waf_logging.tftest.hcl 과 같다(시험 파일마다
# 따로 둬야 한다).

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
    }
  }

  mock_data "aws_region" {
    defaults = {
      region = "ap-northeast-2"
    }
  }
}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id                  = "vpc-0example"
      vpc_cidr                = "192.0.2.0/24"
      public_subnet_ids       = ["subnet-0puba", "subnet-0pubc"]
      private_subnet_ids      = ["subnet-0priva", "subnet-0privc"]
      media_az                = "ap-northeast-2c"
      media_public_subnet_id  = "subnet-0pubc"
      media_private_subnet_id = "subnet-0privc"
      s3_prefix_list_id       = "pl-0example"
    }
  }
}

override_data {
  target = data.terraform_remote_state.certs
  values = {
    outputs = {
      cert_arn_apne2 = "arn:aws:acm:ap-northeast-2:111122223333:certificate/00000000-0000-0000-0000-00000000a002"
      cert_arn_use1  = "arn:aws:acm:us-east-1:111122223333:certificate/00000000-0000-0000-0000-00000000e001"
    }
  }
}

override_data {
  target = data.terraform_remote_state.secrets
  values = {
    outputs = {
      public_key_id              = "K0EXAMPLEKEYID"
      signing_key_secret_arn     = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/signing-key-AbCdEf"
      cdn_secret_arn             = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/cdn-secret-GhIjKl"
      test_stream_key_secret_arn = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/test-stream-key-MnOpQr"
    }
  }
}

override_data {
  target = data.terraform_remote_state.registry
  values = {
    outputs = {
      repo_urls = {
        next             = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/next"
        auth             = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/auth"
        clip             = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/clip"
        "chat-collector" = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/chat-collector"
        "chat-detector"  = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/chat-detector"
      }
    }
  }
}

override_data {
  target = data.terraform_remote_state.runtime
  values = {
    outputs = {
      rds_sg_id         = "sg-0rds"
      rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
      rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
      app_db_users = {
        auth             = "app_auth"
        clip             = "app_clip"
        "chat-collector" = "app_chat_collector"
        "chat-detector"  = "app_chat_detector"
      }
      app_env = {
        auth = {
          DB_HOST     = "db.example.invalid"
          POSTGRES_DB = "pokeclip"
        }
        clip = {
          DB_HOST                  = "db.example.invalid"
          POSTGRES_DB              = "pokeclip"
          BROADCAST_INTAKE_ENABLED = "true"
          BROADCAST_QUEUE_URL      = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-broadcast-clip"
        }
        "chat-collector" = {
          S3_BUCKET           = "pokeclip-prod-chat-archive-example"
          DB_HOST             = "db.example.invalid"
          POSTGRES_DB         = "pokeclip"
          BROADCAST_QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-broadcast-lifecycle-chat.fifo"
          CHZZK_CLIENT_ID     = "chzzk-example-client"
          SOOP_CLIENT_ID      = "soop-example-client"
        }
        "chat-detector" = {
          DB_HOST     = "db.example.invalid"
          POSTGRES_DB = "pokeclip"
        }
      }
      app_secret_arns = {
        auth = {
          JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
          SECRET_STORE_KEY   = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/secret-store-key-YzAbCd"
          INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
        }
        clip = {
          JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
          INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
        }
        "chat-collector" = {
          INTERNAL_API_TOKEN  = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          CHZZK_CLIENT_SECRET = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/chzzk-client-secret-KlMnOp"
          SOOP_CLIENT_SECRET  = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/soop-client-secret-QrStUv"
        }
        "chat-detector" = {
          INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
        }
      }
      app_task_resource_arns = {
        auth = {
          profile_photo_bucket_arn = "arn:aws:s3:::pokeclip-prod-profile-photo-example"
        }
        clip = {
          broadcast_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-clip"
          render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-render"
          render_dlq_arn      = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-render-dlq"
          upload_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-upload"
          upload_dlq_arn      = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-upload-dlq"
          thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-thumbnail"
          clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
        }
      }
      broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
      chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
    }
  }
}

variables {
  image_digests = {
    next             = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    auth             = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
    clip             = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
    "chat-collector" = "sha256:4444444444444444444444444444444444444444444444444444444444444444"
    "chat-detector"  = "sha256:5555555555555555555555555555555555555555555555555555555555555555"
  }
  proxy_ports = {
    live     = 28181
    playback = 28182
    control  = 28183
    health   = 28184
  }
  media_health_path    = "/example-proxy-health"
  ingest_allowed_cidrs = ["198.51.100.0/24"]
  postgres_sslrootcert = "/etc/pokeclip/rds-ca/global-bundle.pem"
}

# --- clip 치우개 종료 경보(POK-588) ---

# 경보 알림 주제의 ARN 을 고정해, 경보가 다른 주제로 보내거나 주제 정책이
# 다른 주제를 가리키면 실패하게 한다.
override_resource {
  target          = aws_sns_topic.platform_alarms
  override_during = plan
  values = {
    arn = "arn:aws:sns:ap-northeast-2:111122223333:pokeclip-prod-platform-alarms"
  }
}

# 주제 정책의 발행 조건(aws:SourceArn)이 이 경보를 가리키는지 plan 에서 보려고
# 경보 ARN 도 고정한다. 둘 다 plan 단계에서 값을 쓰게 한다(override_during).
override_resource {
  target          = aws_cloudwatch_metric_alarm.clip_reaper_ended
  override_during = plan
  values = {
    arn = "arn:aws:cloudwatch:ap-northeast-2:111122223333:alarm:pokeclip-prod-clip-reaper-ended"
  }
}

# 지표 필터는 clip 로그 그룹(모듈이 만든 것)의 치우개 종료 줄을 센다. 패턴
# 글자는 StaleBroadcastReaper 의 로그 문자열 앞머리이고, 뒤의 streamId= 까지
# 넣어 broadcast.reaper.ended_listener_failed 줄은 세지 않는다. 같은 글자가
# 소스에 문자열로 있는지도 본다(로그 문구가 바뀌면 이 시험이 먼저 깨진다).
run "reaper_alarm_counts_ended_lines_in_clip_log_group" {
  command = plan

  assert {
    condition     = aws_cloudwatch_log_metric_filter.clip_reaper_ended.pattern == "\"broadcast.reaper.ended streamId=\""
    error_message = "지표 필터 패턴이 \"broadcast.reaper.ended streamId=\"(따옴표 구절)가 아니다."
  }

  assert {
    condition = strcontains(
      file("../../../../services/clip/src/main/java/com/pokeclip/clip/broadcast/reaper/StaleBroadcastReaper.java"),
      "\"${trim(aws_cloudwatch_log_metric_filter.clip_reaper_ended.pattern, "\"")}",
    )
    error_message = "지표 필터 패턴 글자가 StaleBroadcastReaper.java 의 로그 문자열 앞머리로 없다."
  }

  assert {
    condition = (
      aws_cloudwatch_log_metric_filter.clip_reaper_ended.log_group_name == module.clip.log_group_name &&
      module.clip.log_group_name == "pokeclip-prod-clip"
    )
    error_message = "지표 필터가 clip 서비스 로그 그룹(pokeclip-prod-clip — module.clip 출력)에 걸려 있지 않다."
  }

  assert {
    condition = [
      aws_cloudwatch_log_metric_filter.clip_reaper_ended.name,
      aws_cloudwatch_log_metric_filter.clip_reaper_ended.metric_transformation[0].namespace,
      aws_cloudwatch_log_metric_filter.clip_reaper_ended.metric_transformation[0].name,
      aws_cloudwatch_log_metric_filter.clip_reaper_ended.metric_transformation[0].value,
    ] == ["pokeclip-prod-clip-reaper-ended", "PokeClip/Platform", "ClipReaperEnded", "1"]
    error_message = "지표 필터의 이름 · 지표가 pokeclip-prod-clip-reaper-ended · PokeClip/Platform · ClipReaperEnded · 값 1 이 아니다."
  }
}

# 경보 — 지표 · 네임스페이스는 필터의 것, 5분 합 1 이상 · 1/1 · 데이터 없음 =
# 정상(스위치가 꺼져 있으면 줄이 없어 울리지 않는다), 알림은 경보 주제 하나.
run "reaper_alarm_notifies_topic_on_first_close" {
  command = plan

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.clip_reaper_ended.alarm_name == "pokeclip-prod-clip-reaper-ended" &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.namespace == aws_cloudwatch_log_metric_filter.clip_reaper_ended.metric_transformation[0].namespace &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.metric_name == aws_cloudwatch_log_metric_filter.clip_reaper_ended.metric_transformation[0].name &&
      length(coalesce(aws_cloudwatch_metric_alarm.clip_reaper_ended.dimensions, {})) == 0
    )
    error_message = "치우개 경보의 이름 · 지표가 pokeclip-prod-clip-reaper-ended · 지표 필터의 것이 아니거나 차원이 붙었다."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.clip_reaper_ended.statistic == "Sum" &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.period == 300 &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.evaluation_periods == 1 &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.datapoints_to_alarm == 1 &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.threshold == 1 &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.comparison_operator == "GreaterThanOrEqualToThreshold" &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.treat_missing_data == "notBreaching"
    )
    error_message = "치우개 경보가 5분(300초) 합(Sum) 1 이상 · 1/1 · treat_missing_data = notBreaching 이 아니다."
  }

  assert {
    condition = aws_cloudwatch_metric_alarm.clip_reaper_ended.alarm_actions == toset([
      "arn:aws:sns:ap-northeast-2:111122223333:pokeclip-prod-platform-alarms",
    ])
    error_message = "치우개 경보의 알림이 platform 경보 주제 하나가 아니다."
  }
}

# 경보 주제 — 이메일 구독은 저장소 밖이라 구독 자원이 없다. 주제 정책은 기본
# 정책(Principal * · SourceOwner) 대신 명시 정책이다 — 계정 루트는 읽기
# 하나, 발행은 이 루트의 경보가 보내는 CloudWatch 뿐이다.
run "platform_alarm_topic_is_private_and_cloudwatch_only_publishes" {
  command = plan

  assert {
    condition = (
      aws_sns_topic.platform_alarms.name == "pokeclip-prod-platform-alarms" &&
      aws_sns_topic_policy.platform_alarms.arn == aws_sns_topic.platform_alarms.arn &&
      output.platform_alarm_topic_arn == "arn:aws:sns:ap-northeast-2:111122223333:pokeclip-prod-platform-alarms"
    )
    error_message = "경보 주제가 pokeclip-prod-platform-alarms 가 아니거나 정책 · 출력 platform_alarm_topic_arn 이 그 주제를 가리키지 않는다."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(aws_sns_topic_policy.platform_alarms.policy).Statement :
      statement.Effect == "Allow" &&
      try(statement.Principal.AWS, "") != "*" &&
      try(statement.Principal, "") != "*" &&
      statement.Resource == aws_sns_topic.platform_alarms.arn
    ])
    error_message = "경보 주제 정책에 Principal * 가 있거나 Allow 밖 문장 · 다른 자원이 있다."
  }

  assert {
    condition = [
      for statement in jsondecode(aws_sns_topic_policy.platform_alarms.policy).Statement :
      [statement.Principal, statement.Action]
      ] == [
      [{ AWS = "arn:aws:iam::111122223333:root" }, ["SNS:GetTopicAttributes"]],
      [{ Service = "cloudwatch.amazonaws.com" }, "SNS:Publish"],
    ]
    error_message = "경보 주제 정책이 계정 루트 읽기(GetTopicAttributes) · CloudWatch 발행(Publish) 두 문장이 아니다."
  }

  assert {
    condition = (
      keys(jsondecode(aws_sns_topic_policy.platform_alarms.policy).Statement[1].Condition) == ["ArnEquals", "StringEquals"] &&
      tolist(jsondecode(aws_sns_topic_policy.platform_alarms.policy).Statement[1].Condition.ArnEquals["aws:SourceArn"]) == tolist([aws_cloudwatch_metric_alarm.clip_reaper_ended.arn]) &&
      aws_cloudwatch_metric_alarm.clip_reaper_ended.arn == "arn:aws:cloudwatch:ap-northeast-2:111122223333:alarm:pokeclip-prod-clip-reaper-ended" &&
      jsondecode(aws_sns_topic_policy.platform_alarms.policy).Statement[1].Condition.StringEquals == { "aws:SourceAccount" = "111122223333" }
    )
    error_message = "CloudWatch 발행 문장이 이 루트의 치우개 경보(aws:SourceArn) · 이 계정(aws:SourceAccount)으로 좁혀져 있지 않다."
  }
}
