# O7d(prod/platform 최초 apply) 직전에 맞춘 env · 런타임 계약 셋.
#
# 1. postgres_sslrootcert 기본값 — auth · clip 이미지가 RDS CA 번들을 두는
#    자리(services/auth · clip Dockerfile 의
#    /etc/pokeclip/rds-ca/global-bundle.pem). iam 에서 POSTGRES_PASSWORD 가
#    env · secrets 에 있으면 앱이 부팅을 거부한다.
# 2. clip 의 COLLECTOR_BASE_URL — clip application.yml 이 ${COLLECTOR_BASE_URL:}
#    로 읽는다. 비면 채팅 문 셋이 503 이다.
# 3. 런타임 SQS 큐 ARN 의 FIFO 접미(.fifo) — 큐 이름은 영숫자 · - · _ 이고
#    FIFO 큐는 .fifo 로 끝난다(SQS CreateQueue API 참조).
#
# 앞 루트 상태 흉내 · 가짜 값은 apply_order.tftest.hcl 과 같다(시험 파일마다
# 따로 둬야 한다). postgres_sslrootcert 는 기본값을 보려고 넘기지 않는다.

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

  # apply 로 도는 run 에서 provider 가 ARN 꼴을 검사하는 참조에 쓸 값.
  mock_resource "aws_lb" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/example/0123456789abcdef"
    }
  }

  mock_resource "aws_lb_listener" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:listener/app/example/0123456789abcdef/0123456789abcdef"
    }
  }

  mock_resource "aws_lb_target_group" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example/0123456789abcdef"
    }
  }

  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::111122223333:role/pokeclip/prod/example"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:ap-northeast-2:111122223333:log-group:example"
    }
  }

  mock_resource "aws_service_discovery_service" {
    defaults = {
      arn = "arn:aws:servicediscovery:ap-northeast-2:111122223333:service/srv-0example"
    }
  }

  mock_resource "aws_ecs_cluster" {
    defaults = {
      arn = "arn:aws:ecs:ap-northeast-2:111122223333:cluster/pokeclip-prod"
    }
  }

  mock_resource "aws_ecs_task_definition" {
    defaults = {
      arn = "arn:aws:ecs:ap-northeast-2:111122223333:task-definition/example:1"
    }
  }

  mock_resource "aws_wafv2_web_acl" {
    defaults = {
      arn = "arn:aws:wafv2:ap-northeast-2:111122223333:regional/webacl/pokeclip-prod-regional/00000000-0000-0000-0000-000000000000"
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
}

# --- CA 번들 경로 기본값 ---

run "iam_default_points_ssl_root_cert_at_image_bundle" {
  command = plan

  assert {
    condition = alltrue([
      for service in ["auth", "clip"] :
      local.service_env[service]["POSTGRES_AUTH"] == "iam" &&
      local.service_env[service]["POSTGRES_SSLROOTCERT"] == "/etc/pokeclip/rds-ca/global-bundle.pem"
    ])
    error_message = "기본값에서 auth · clip env 의 POSTGRES_AUTH · POSTGRES_SSLROOTCERT 가 iam · 이미지 CA 번들 경로(/etc/pokeclip/rds-ca/global-bundle.pem)가 아니다."
  }

  assert {
    condition = alltrue([
      for service in ["auth", "clip"] :
      !contains(keys(local.service_env[service]), "POSTGRES_PASSWORD") &&
      !contains(keys(local.service_secrets[service]), "POSTGRES_PASSWORD")
    ])
    error_message = "iam 인데 POSTGRES_PASSWORD 가 auth · clip 의 env 나 secrets 에 있다 — 앱이 부팅을 거부한다."
  }
}

# --- clip 의 수집기 주소 ---

run "clip_gets_same_collector_base_url_as_auth" {
  command = plan

  assert {
    condition = (
      local.service_env.clip["COLLECTOR_BASE_URL"] == "http://chat-collector.pokeclip.internal:8083" &&
      local.service_env.clip["COLLECTOR_BASE_URL"] == local.service_env.auth["COLLECTOR_BASE_URL"]
    )
    error_message = "clip env 의 COLLECTOR_BASE_URL 이 없거나 auth 와 다르다 — 비면 clip 의 채팅 문 셋이 503 이다."
  }
}

# --- 런타임 SQS 큐 ARN 꼴 ---

run "runtime_contract_accepts_fifo_queue_arn" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id         = "sg-0rds"
        rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users      = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env           = { auth = {}, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns   = { auth = {}, clip = {} }
        app_task_resource_arns = {
          auth = {}
          clip = { broadcast_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo" }
        }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  assert {
    condition     = local.task_resource_arns.clip.broadcast_queue_arn == "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo"
    error_message = "FIFO 큐 ARN 이 작업 역할 대상으로 넘어오지 않았다."
  }
}

run "runtime_contract_rejects_dot_inside_queue_name" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id               = "sg-0rds"
        rds_resource_id         = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn       = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users            = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env                 = { auth = {}, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns         = { auth = {}, clip = {} }
        app_task_resource_arns  = { auth = {}, clip = { broadcast_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:a.b" } }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

run "runtime_contract_rejects_wildcard_fifo_queue_arn" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id               = "sg-0rds"
        rds_resource_id         = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn       = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users            = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env                 = { auth = {}, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns         = { auth = {}, clip = {} }
        app_task_resource_arns  = { auth = {}, clip = { broadcast_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-*.fifo" } }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}
