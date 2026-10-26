# 런타임 출력이 auth · clip 의 부팅 거부 조건에 걸리면 plan 에서 멈춘다
# (terraform_data.runtime_contract).
#
# 1. app_db_auth = iam 인데 auth · clip 에 POSTGRES_PASSWORD 가 오면 앱이
#    부팅을 거부한다(db-support DataSourceLoginConfiguration
#    .rejectLeftoverPassword).
# 2. auth 에 STREAM_KEY_RESOLVE_TOKEN 이 오면 STREAM_KEY_RESOLVE_ALLOWED_CIDRS
#    가 비어 있을 때 부팅을 거부한다(auth StreamKeyResolveSecurityConfig
#    .requireSafeToOpen).
#
# 앞 루트 상태 흉내 · 가짜 값은 o7d_env.tftest.hcl 과 같다(시험 파일마다 따로
# 둬야 한다).

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

# --- iam 에서 남은 POSTGRES_PASSWORD ---

run "rejects_postgres_password_secret_under_iam" {
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
        app_secret_arns = {
          auth = {}
          clip = { POSTGRES_PASSWORD = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/db-password-KlMnOp" }
        }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

run "rejects_postgres_password_auth_secret_under_iam" {
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
        app_secret_arns = {
          auth = { POSTGRES_PASSWORD = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/db-password-KlMnOp" }
          clip = {}
        }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# app_env 의 POSTGRES_PASSWORD 도 앱이 읽는다. 장부 16 검사(평문 env 의 비밀
# 이름)가 먼저 막지 않게 그 이름을 비밀 아님 목록에 넣어, 이 검사만 본다.
run "rejects_postgres_password_app_env_under_iam" {
  command = plan

  variables {
    app_env_non_secret_names = ["POSTGRES_PASSWORD"]
  }

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id               = "sg-0rds"
        rds_resource_id         = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn       = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users            = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env                 = { auth = {}, clip = { POSTGRES_PASSWORD = "fixture-password" }, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns         = { auth = {}, clip = {} }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# password 모드에서는 POSTGRES_PASSWORD 가 접속 비밀번호다 — 막지 않는다.
run "allows_postgres_password_secret_under_password_auth" {
  command = plan

  variables {
    app_db_auth = "password"
  }

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id         = "sg-0rds"
        rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users      = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env           = { auth = {}, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns = {
          auth = { POSTGRES_PASSWORD = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/db-password-KlMnOp" }
          clip = {}
        }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  assert {
    condition     = local.service_secrets.auth["POSTGRES_PASSWORD"] == "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/db-password-KlMnOp"
    error_message = "password 모드의 POSTGRES_PASSWORD 가 auth secrets 로 넘어가지 않는다."
  }
}

# --- resolve 전용 토큰과 허용 대역 ---

run "rejects_resolve_token_without_allowed_cidrs" {
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
        app_secret_arns = {
          auth = { STREAM_KEY_RESOLVE_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/stream-key-resolve-token-QrStUv" }
          clip = {}
        }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

run "rejects_resolve_token_with_blank_allowed_cidrs" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id         = "sg-0rds"
        rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users      = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env           = { auth = { STREAM_KEY_RESOLVE_ALLOWED_CIDRS = " " }, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns = {
          auth = { STREAM_KEY_RESOLVE_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/stream-key-resolve-token-QrStUv" }
          clip = {}
        }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

run "allows_resolve_token_with_allowed_cidrs" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id         = "sg-0rds"
        rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users      = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env           = { auth = { STREAM_KEY_RESOLVE_ALLOWED_CIDRS = "192.0.2.0/24" }, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns = {
          auth = { STREAM_KEY_RESOLVE_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/stream-key-resolve-token-QrStUv" }
          clip = {}
        }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  assert {
    condition     = local.service_env.auth["STREAM_KEY_RESOLVE_ALLOWED_CIDRS"] == "192.0.2.0/24"
    error_message = "허용 대역이 auth env 로 넘어가지 않는다."
  }
}

# 토큰이 계약과 달리 app_env 로 와도 앱은 같은 이름으로 읽는다. 장부 16
# 검사(평문 env 의 비밀 이름)가 먼저 막지 않게 그 이름을 비밀 아님 목록에 넣어,
# 이 검사만 본다.
run "rejects_resolve_token_from_env_without_allowed_cidrs" {
  command = plan

  variables {
    app_env_non_secret_names = ["STREAM_KEY_RESOLVE_TOKEN"]
  }

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id               = "sg-0rds"
        rds_resource_id         = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn       = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users            = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env                 = { auth = { STREAM_KEY_RESOLVE_TOKEN = "fixture-resolve-token" }, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns         = { auth = {}, clip = {} }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 빈 토큰(공백뿐 포함)이면 창구가 닫혀 허용 대역을 요구하지 않는다(auth
# StreamKeyResolveAccessProperties — token.isBlank()).
run "allows_blank_resolve_token_from_env_without_allowed_cidrs" {
  command = plan

  variables {
    app_env_non_secret_names = ["STREAM_KEY_RESOLVE_TOKEN"]
  }

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id               = "sg-0rds"
        rds_resource_id         = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn       = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users            = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env                 = { auth = { STREAM_KEY_RESOLVE_TOKEN = " " }, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns         = { auth = {}, clip = {} }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  assert {
    condition     = local.service_env.auth["STREAM_KEY_RESOLVE_TOKEN"] == " "
    error_message = "빈 resolve 토큰이 auth env 로 넘어가지 않는다."
  }
}

# 허용 대역을 비밀로 줘도 앱은 같은 이름으로 읽는다 — 값은 plan 에서 볼 수
# 없으므로 있으면 인정한다.
run "allows_resolve_token_with_allowed_cidrs_secret" {
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
        app_secret_arns = {
          auth = {
            STREAM_KEY_RESOLVE_TOKEN         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/stream-key-resolve-token-QrStUv"
            STREAM_KEY_RESOLVE_ALLOWED_CIDRS = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/stream-key-resolve-cidrs-WxYzAb"
          }
          clip = {}
        }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  assert {
    condition     = local.service_secrets.auth["STREAM_KEY_RESOLVE_ALLOWED_CIDRS"] == "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/stream-key-resolve-cidrs-WxYzAb"
    error_message = "비밀로 준 허용 대역이 auth secrets 로 넘어가지 않는다."
  }
}
