# prod/platform 루트의 chat 두 서버(chat-collector · chat-detector — POK-306)를
# mock provider 로 단언한다. 두 서버는 auth · clip 과 같은 ECS Fargate 서비스지만
# 공개 ALB · 대상 그룹 없이 Cloud Map(<이름>.pokeclip.internal)으로만 받는다.
# 그래서 next · auth · clip 의 태스크 정의 입력 · 대상 그룹 · 공개 ALB 규칙이
# 그대로인지도 여기서 못 박는다.
#
# 앞 루트 상태 흉내 · 가짜 값은 services.tftest.hcl 과 같다(시험 파일마다 따로
# 둬야 한다). 런타임 흉내는 PR-1 뒤 꼴이다 — chat-collector 에 CHZZK_CLIENT_ID ·
# SOOP_CLIENT_ID(app_env) · SOOP_CLIENT_SECRET(app_secret_arns)이 있다.

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
  postgres_sslrootcert = "/etc/pokeclip/rds-ca/global-bundle.pem"
}

# --- 이미지 · 크기 ---

run "chat_services_pin_digests_and_sizes" {
  command = plan

  assert {
    condition = (
      local.service_images["chat-collector"] == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/chat-collector@sha256:4444444444444444444444444444444444444444444444444444444444444444" &&
      local.service_images["chat-detector"] == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/chat-detector@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    )
    error_message = "chat 이미지가 registry repo_urls + 다이제스트가 아니다."
  }

  # 포트는 두 서버 application.yml 의 server.port 다. 크기는 auth · clip 과 같은
  # 꼴로 시작한다(collector 는 SOOP 채팅 탭 · 적재 바구니가 있어 1 vCPU · 2 GB).
  assert {
    condition = (
      var.chat_services["chat-collector"].port == 8083 &&
      var.chat_services["chat-collector"].cpu == 1024 &&
      var.chat_services["chat-collector"].memory == 2048 &&
      var.chat_services["chat-detector"].port == 8084 &&
      var.chat_services["chat-detector"].cpu == 512 &&
      var.chat_services["chat-detector"].memory == 1024
    )
    error_message = "chat_services 기본값이 collector 8083 · 1024 · 2048, detector 8084 · 512 · 1024 가 아니다."
  }
}

run "rejects_chat_services_without_both_keys" {
  command = plan

  variables {
    chat_services = {
      chat-collector = { port = 8083, cpu = 1024, memory = 2048 }
    }
  }

  expect_failures = [var.chat_services]
}

run "rejects_chat_image_tag" {
  command = plan

  variables {
    image_digests = {
      next             = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      auth             = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
      clip             = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
      "chat-collector" = "latest"
      "chat-detector"  = "sha256:5555555555555555555555555555555555555555555555555555555555555555"
    }
  }

  expect_failures = [var.image_digests]
}

# --- env · 비밀 ---

# collector 는 방송 편지(BROADCAST_INTAKE_ENABLED)로 세션을 연다. 기능 스위치
# 기본값 = 재부착 · 중계 · 방송 정보 · SOOP(지켜보기 · 채팅) 켬, 떼기 꺼짐(kty
# 2026-10-26 — 떼기는 clip 치우개와 함께 O7e 뒤 운영자 결정). 채팅 원본
# 보관 창고 이름 S3_BUCKET 은 런타임 app_env 가 준다. CHZZK_ENABLED(옛 한 채널
# 경로 — intake 와 같이 켜면 부팅 거부) · CHAT_SYNC_OFFSET_MS(기본 3900)는
# 넣지 않는다.
run "chat_collector_env_and_secrets" {
  command = plan

  assert {
    condition = jsonencode(local.service_env["chat-collector"]) == jsonencode({
      AUTH_BASE_URL            = "http://auth.pokeclip.internal:8082"
      BROADCAST_INFO_ENABLED   = "true"
      BROADCAST_INTAKE_ENABLED = "true"
      BROADCAST_QUEUE_URL      = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-broadcast-lifecycle-chat.fifo"
      CHAT_DETACH_ENABLED      = "false"
      CHAT_REATTACH_ENABLED    = "true"
      CHAT_RELAY_ENABLED       = "true"
      CHZZK_CLIENT_ID          = "chzzk-example-client"
      CLIP_BASE_URL            = "http://clip.pokeclip.internal:8081"
      DB_HOST                  = "db.example.invalid"
      POSTGRES_AUTH            = "iam"
      POSTGRES_DB              = "pokeclip"
      POSTGRES_SSLROOTCERT     = "/etc/pokeclip/rds-ca/global-bundle.pem"
      POSTGRES_USER            = "app_chat_collector"
      S3_BUCKET                = "pokeclip-prod-chat-archive-example"
      SOOP_CHAT_ENABLED        = "true"
      SOOP_CLIENT_ID           = "soop-example-client"
      SOOP_WATCH_ENABLED       = "true"
    })
    error_message = "chat-collector env 가 런타임 app_env + DB 접속 + 받기 · 주소 · 기능 스위치 기본값이 아니다."
  }

  assert {
    condition = !anytrue([
      for name in ["CHZZK_ENABLED", "CHAT_SYNC_OFFSET_MS", "POSTGRES_PASSWORD"] :
      contains(keys(local.service_env["chat-collector"]), name)
    ])
    error_message = "chat-collector env 에 CHZZK_ENABLED · CHAT_SYNC_OFFSET_MS · POSTGRES_PASSWORD 중 하나가 있다."
  }

  assert {
    condition     = local.service_secrets["chat-collector"] == data.terraform_remote_state.runtime.outputs.app_secret_arns["chat-collector"]
    error_message = "chat-collector secrets 가 런타임 app_secret_arns 와 다르다."
  }
}

run "chat_detector_env_and_secrets" {
  command = plan

  assert {
    condition = jsonencode(local.service_env["chat-detector"]) == jsonencode({
      CLIP_BASE_URL        = "http://clip.pokeclip.internal:8081"
      COLLECTOR_BASE_URL   = "http://chat-collector.pokeclip.internal:8083"
      DB_HOST              = "db.example.invalid"
      POSTGRES_AUTH        = "iam"
      POSTGRES_DB          = "pokeclip"
      POSTGRES_SSLROOTCERT = "/etc/pokeclip/rds-ca/global-bundle.pem"
      POSTGRES_USER        = "app_chat_detector"
    })
    error_message = "chat-detector env 가 런타임 app_env + DB 접속 + clip · collector 주소가 아니다."
  }

  assert {
    condition     = local.service_secrets["chat-detector"] == data.terraform_remote_state.runtime.outputs.app_secret_arns["chat-detector"]
    error_message = "chat-detector secrets 가 런타임 app_secret_arns 와 다르다."
  }
}

# 포트는 두 서버 application.yml 의 server.port 맨값(자리 표시자 없음)이다 —
# 여기서 바꾸면 SG · 주소 · 컨테이너 포트만 따라가고 앱은 옛 포트에서 듣는다.
run "rejects_chat_collector_port_other_than_app_port" {
  command = plan

  variables {
    chat_services = {
      chat-collector = { port = 18083, cpu = 1024, memory = 2048 }
      chat-detector  = { port = 8084, cpu = 512, memory = 1024 }
    }
  }

  expect_failures = [var.chat_services]
}

run "rejects_chat_detector_port_other_than_app_port" {
  command = plan

  variables {
    chat_services = {
      chat-collector = { port = 8083, cpu = 1024, memory = 2048 }
      chat-detector  = { port = 18084, cpu = 512, memory = 1024 }
    }
  }

  expect_failures = [var.chat_services]
}

# 이 루트가 정하는 env 가 런타임 app_env 의 같은 이름을 이긴다.
run "platform_chat_env_wins_over_runtime_app_env" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id         = "sg-0rds"
        rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users      = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env = {
          auth = {}
          clip = {}
          "chat-collector" = {
            S3_BUCKET                = "pokeclip-prod-chat-archive-example"
            BROADCAST_INTAKE_ENABLED = "false"
            AUTH_BASE_URL            = "http://localhost:8082"
            SOOP_CHAT_ENABLED        = "false"
          }
          "chat-detector" = { CLIP_BASE_URL = "http://localhost:8081" }
        }
        app_secret_arns         = { auth = {}, clip = {} }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  assert {
    condition = (
      local.service_env["chat-collector"]["BROADCAST_INTAKE_ENABLED"] == "true" &&
      local.service_env["chat-collector"]["AUTH_BASE_URL"] == "http://auth.pokeclip.internal:8082" &&
      local.service_env["chat-collector"]["SOOP_CHAT_ENABLED"] == "true" &&
      local.service_env["chat-detector"]["CLIP_BASE_URL"] == "http://clip.pokeclip.internal:8081"
    )
    error_message = "런타임 app_env 의 chat env 가 platform 값을 덮었다."
  }
}

# --- 기능 스위치(chat_collector_features) ---

# 기본값과 다르게 둔 스위치가 그대로 env 로 간다(재부착은 떼기를 켜려고 켠 채
# 둔다). 재부착은 받기가 켜져야 뜨는데 이 루트는 받기를 늘 켠다.
run "chat_collector_features_map_to_env" {
  command = plan

  variables {
    chat_collector_features = {
      reattach   = true
      relay      = false
      liveinfo   = false
      detach     = true
      soop_watch = false
      soop_chat  = false
    }
  }

  assert {
    condition = (
      local.service_env["chat-collector"]["CHAT_REATTACH_ENABLED"] == "true" &&
      local.service_env["chat-collector"]["CHAT_RELAY_ENABLED"] == "false" &&
      local.service_env["chat-collector"]["BROADCAST_INFO_ENABLED"] == "false" &&
      local.service_env["chat-collector"]["CHAT_DETACH_ENABLED"] == "true" &&
      local.service_env["chat-collector"]["SOOP_WATCH_ENABLED"] == "false" &&
      local.service_env["chat-collector"]["SOOP_CHAT_ENABLED"] == "false" &&
      local.service_env["chat-collector"]["BROADCAST_INTAKE_ENABLED"] == "true"
    )
    error_message = "chat_collector_features 가 CHAT_* · BROADCAST_INFO_ENABLED · SOOP_* env 로 그대로 가지 않거나 받기가 꺼졌다."
  }
}

# 떼기는 재부착 회차 안에서 돈다(DetachSwitchGuard — 혼자 켜면 부팅 거부).
run "rejects_detach_without_reattach" {
  command = plan

  variables {
    chat_collector_features = {
      reattach   = false
      relay      = false
      liveinfo   = true
      detach     = true
      soop_watch = true
      soop_chat  = true
    }
  }

  expect_failures = [var.chat_collector_features]
}

# SOOP 채팅은 지켜보기가 고른 방송에 붙는다(혼자 켜면 부팅 거부).
run "rejects_soop_chat_without_soop_watch" {
  command = plan

  variables {
    chat_collector_features = {
      reattach   = false
      relay      = false
      liveinfo   = true
      detach     = false
      soop_watch = false
      soop_chat  = true
    }
  }

  expect_failures = [var.chat_collector_features]
}

# --- next · auth · clip 은 그대로(변경 0) ---

# chat 두 서버는 공개 ALB · 대상 그룹을 쓰지 않는다. next · auth · clip 모듈에
# 넘기는 입력(env · 비밀 · 이미지 · 크기 · 대상 그룹)과 공개 ALB 대상 그룹 ·
# 리스너 규칙 · 그 SG 규칙이 chat 을 더하기 전과 같아야 한다 — 값이 하나라도
# 바뀌면 태스크 정의가 새 판으로 바뀌어 세 서비스가 다시 배포된다.
run "public_services_unchanged_by_chat" {
  command = plan

  assert {
    condition = (
      length(local.service_env.next) == 0 &&
      jsonencode(local.service_env.auth) == jsonencode({
        CLIP_BASE_URL                   = "http://clip.pokeclip.internal:8081"
        COLLECTOR_BASE_URL              = "http://chat-collector.pokeclip.internal:8083"
        CORS_ALLOWED_ORIGINS            = "https://app.pokeclip.com"
        DB_HOST                         = "db.example.invalid"
        FORWARD_HEADERS_STRATEGY        = "native"
        FORWARD_HEADERS_TRUSTED_PROXIES = "192.0.2.0/24"
        POSTGRES_AUTH                   = "iam"
        POSTGRES_DB                     = "pokeclip"
        POSTGRES_SSLROOTCERT            = "/etc/pokeclip/rds-ca/global-bundle.pem"
        POSTGRES_USER                   = "app_auth"
        STREAM_KEY_SECRET_REF_PREFIX    = "pokeclip/prod/stream-key/"
        STREAM_KEY_SECRET_RETIRE_FROM   = "aws"
        STREAM_KEY_SECRET_STORE_TYPE    = "aws"
      }) &&
      jsonencode(local.service_env.clip) == jsonencode({
        AUTH_BASE_URL            = "http://auth.pokeclip.internal:8082"
        BROADCAST_INTAKE_ENABLED = "true"
        BROADCAST_QUEUE_URL      = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-broadcast-clip"
        CLOUDFRONT_KEY_PAIR_ID   = "K0EXAMPLEKEYID"
        COLLECTOR_BASE_URL       = "http://chat-collector.pokeclip.internal:8083"
        CORS_ALLOWED_ORIGINS     = "https://app.pokeclip.com"
        DB_HOST                  = "db.example.invalid"
        MEDIA_BASE_URL           = "https://media.pokeclip.com"
        PLAYBACK_COOKIE_DOMAIN   = ".pokeclip.com"
        PLAYBACK_REQUIRED        = "true"
        POSTGRES_AUTH            = "iam"
        POSTGRES_DB              = "pokeclip"
        POSTGRES_SSLROOTCERT     = "/etc/pokeclip/rds-ca/global-bundle.pem"
        POSTGRES_USER            = "app_clip"
      })
    )
    error_message = "chat 을 더하며 next · auth · clip 의 태스크 env 가 바뀌었다."
  }

  assert {
    condition = (
      length(local.service_secrets.next) == 0 &&
      local.service_secrets.auth == data.terraform_remote_state.runtime.outputs.app_secret_arns.auth &&
      jsonencode(local.service_secrets.clip) == jsonencode(merge(
        data.terraform_remote_state.runtime.outputs.app_secret_arns.clip,
        { CLOUDFRONT_PRIVATE_KEY_PEM = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/signing-key-AbCdEf" },
      ))
    )
    error_message = "chat 을 더하며 next · auth · clip 의 태스크 비밀이 바뀌었다."
  }

  assert {
    condition = (
      local.service_images.next == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/next@sha256:1111111111111111111111111111111111111111111111111111111111111111" &&
      local.service_images.auth == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/auth@sha256:2222222222222222222222222222222222222222222222222222222222222222" &&
      local.service_images.clip == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/clip@sha256:3333333333333333333333333333333333333333333333333333333333333333" &&
      toset(keys(var.services)) == toset(["next", "auth", "clip"])
    )
    error_message = "chat 을 더하며 next · auth · clip 의 이미지가 바뀌었거나 services(공개 ALB 원천)에 chat 이 들어갔다."
  }

  assert {
    condition = (
      toset(keys(local.service_target_group_arns)) == toset(["next", "auth", "clip"]) &&
      length(local.service_target_group_arns.clip) == 2 &&
      toset(keys(local.public_target_groups)) == toset(["next", "auth", "clip", "clip-sse"]) &&
      toset(keys(local.public_listener_rules)) == toset(["auth", "clip-sse", "clip", "next"]) &&
      toset(keys(aws_vpc_security_group_egress_rule.public_alb_to_service)) == toset(["next", "auth", "clip"]) &&
      toset(keys(aws_vpc_security_group_ingress_rule.service_from_public_alb)) == toset(["next", "auth", "clip"]) &&
      toset(keys(local.service_sg_ids)) == toset(["next", "auth", "clip"])
    )
    error_message = "chat 을 더하며 공개 ALB 대상 그룹 · 리스너 규칙 · 그 SG 규칙 · service_sg_ids 의 키가 바뀌었다."
  }

  assert {
    condition     = toset(keys(local.egress_targets.next)) == toset(["aws_api", "s3", "auth", "clip"])
    error_message = "chat 을 더하며 next 이그레스가 바뀌었다."
  }
}

# --- 배포 비율 ---

# chat-detector 의 사건 묶음(SpikeEpisodes)은 프로세스 메모리에 산다. 재배포 때
# 옛 태스크와 새 태스크가 겹치면 인접 창을 둘이 나눠 잡아 사건이 갈라지고, 시작
# 시각이 다른 카드가 두 장 나갈 수 있다. 그래서 detector 만 최소 0 · 최대 100(옛
# 태스크를 먼저 내림)으로 둔다. 나머지 넷은 비율을 넘기지 않는다(ECS 기본 최소
# 100 · 최대 200 그대로 — plan 변화 0).
run "chat_detector_deploys_without_overlap" {
  command = plan

  assert {
    condition = (
      module.chat_detector.deployment_minimum_healthy_percent == 0 &&
      module.chat_detector.deployment_maximum_percent == 100
    )
    error_message = "chat-detector 배포 비율이 최소 0 · 최대 100 이 아니다(재배포 때 두 태스크가 겹친다)."
  }

  assert {
    condition = alltrue([
      for service in [module.next, module.auth, module.clip, module.chat_collector] :
      service.deployment_minimum_healthy_percent == null &&
      service.deployment_maximum_percent == null
    ])
    error_message = "next · auth · clip · chat-collector 에 배포 비율이 들어갔다(지정하지 않아야 plan 이 그대로다)."
  }
}

# --- 작업 역할(코드가 부르는 동작만) ---

# collector 는 자기 방송 편지 큐 하나만 받는다 — SqsIntakeRunner.java 152
# (GetQueueAttributes — 부팅 때 가시성 시한 관측) · 254(ReceiveMessage) ·
# 457(DeleteMessage). 채팅 원본 보관 창고는 chat/ 아래만 쓰고 지운다 —
# S3ArchiveUploader.java 22(PutObject) · S3ArchivePurge.java 50(ListObjectsV2 —
# s3:ListBucket) · 84(DeleteObjects — 객체마다 s3:DeleteObject).
run "chat_task_roles_allow_intake_and_own_db_user" {
  command = plan

  assert {
    condition = jsonencode(local.task_role_statements["chat-collector"]) == jsonencode([
      {
        sid       = "ChatBroadcastIntake"
        actions   = ["sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ReceiveMessage"]
        resources = ["arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo"]
      },
      {
        sid       = "ChatArchiveObjects"
        actions   = ["s3:DeleteObject", "s3:PutObject"]
        resources = ["arn:aws:s3:::pokeclip-prod-chat-archive-example/chat/*"]
      },
      {
        sid       = "ChatArchiveList"
        actions   = ["s3:ListBucket"]
        resources = ["arn:aws:s3:::pokeclip-prod-chat-archive-example"]
      },
      {
        sid       = "DbConnect"
        actions   = ["rds-db:connect"]
        resources = ["arn:aws:rds-db:ap-northeast-2:111122223333:dbuser:db-ABCDEFGHIJKLMNOPQRSTUVWXYZ/app_chat_collector"]
      },
    ])
    error_message = "chat-collector 작업 역할이 chat 큐 받기(Receive · Delete · GetQueueAttributes) · 보관 창고 chat/ 쓰기 · 지우기 · 목록 · 자기 DB 사용자 접속이 아니다."
  }

  assert {
    condition = jsonencode(local.task_role_statements["chat-detector"]) == jsonencode([
      {
        sid       = "DbConnect"
        actions   = ["rds-db:connect"]
        resources = ["arn:aws:rds-db:ap-northeast-2:111122223333:dbuser:db-ABCDEFGHIJKLMNOPQRSTUVWXYZ/app_chat_detector"]
      },
    ])
    error_message = "chat-detector 작업 역할이 자기 DB 사용자 접속 하나가 아니다."
  }
}

run "password_db_auth_drops_chat_rds_db_connect" {
  command = plan

  variables {
    app_db_auth = "password"
  }

  assert {
    condition = (
      toset([for statement in local.task_role_statements["chat-collector"] : statement.sid]) == toset(["ChatBroadcastIntake", "ChatArchiveObjects", "ChatArchiveList"]) &&
      length(local.task_role_statements["chat-detector"]) == 0 &&
      alltrue([
        for service in ["chat-collector", "chat-detector"] :
        !contains(keys(local.service_env[service]), "POSTGRES_AUTH")
      ])
    )
    error_message = "password 인데 chat 에 rds-db:connect 문장이나 POSTGRES_AUTH env 가 남았다."
  }
}

# --- SG 규칙 — SG id 가 계산 속성이라 apply ---

run "chat_security_group_pairs" {
  command = apply

  # 이그레스: 공통(AWS API · S3) + RDS + 부르는 상대만(collector 는 SOOP 채팅
  # 소켓 둘을 더 둔다).
  assert {
    condition = (
      toset(keys(local.egress_targets["chat-collector"])) == toset(["aws_api", "s3", "rds", "auth", "clip", "soop_chat_8041", "soop_chat_9001"]) &&
      toset(keys(local.egress_targets["chat-detector"])) == toset(["aws_api", "s3", "rds", "clip", "chat-collector"]) &&
      toset(keys(local.egress_targets.auth)) == toset(["aws_api", "s3", "rds", "clip", "chat-collector"]) &&
      toset(keys(local.egress_targets.clip)) == toset(["aws_api", "s3", "rds", "auth", "chat-collector"]) &&
      alltrue([
        for service in ["chat-collector", "chat-detector"] :
        local.egress_targets[service].rds.port == 5432 && local.egress_targets[service].rds.security_group_id == "sg-0rds"
      ])
    )
    error_message = "chat · auth · clip 이그레스 키가 공통 + RDS + 부르는 상대가 아니다."
  }

  # SOOP 채팅 소켓(chat_port + 1 — 표본의 8040 · 9000 에서 8041 · 9001)은
  # collector 만 연다. 다른 서비스에는 그 포트가 어느 상대로도 없다.
  assert {
    condition = (
      try(
        local.egress_targets["chat-collector"].soop_chat_8041.port == 8041 &&
        local.egress_targets["chat-collector"].soop_chat_8041.cidr_ipv4 == "0.0.0.0/0" &&
        local.egress_targets["chat-collector"].soop_chat_9001.port == 9001 &&
        local.egress_targets["chat-collector"].soop_chat_9001.cidr_ipv4 == "0.0.0.0/0",
        false,
      ) &&
      alltrue([
        for service in ["chat-detector", "auth", "clip", "next"] :
        alltrue([for target in values(local.egress_targets[service]) : !contains([8041, 9001], target.port)])
      ])
    )
    error_message = "SOOP 채팅 소켓 이그레스(tcp 8041 · 9001 → 0.0.0.0/0)가 collector 에 없거나 다른 서비스에 있다."
  }

  # 짝 여섯: 이그레스(부르는 쪽) · 인그레스(받는 쪽)가 같은 포트 · 상대 SG.
  # auth → collector(탈퇴 정리) · clip → collector(채팅 문 셋) · detector →
  # collector(채팅 읽기) · collector → auth(열쇠 조회) · collector → clip(재부착 ·
  # 중계 · 방송 정보) · detector → clip(카드).
  assert {
    condition = (
      local.egress_targets.auth["chat-collector"].port == 8083 &&
      local.egress_targets.auth["chat-collector"].security_group_id == module.chat_collector.sg_id &&
      local.egress_targets.clip["chat-collector"].port == 8083 &&
      local.egress_targets.clip["chat-collector"].security_group_id == module.chat_collector.sg_id &&
      local.egress_targets["chat-detector"]["chat-collector"].port == 8083 &&
      local.egress_targets["chat-detector"]["chat-collector"].security_group_id == module.chat_collector.sg_id &&
      toset(keys(aws_vpc_security_group_ingress_rule.chat_collector_from_service)) == toset(["auth", "clip", "chat-detector"]) &&
      alltrue([
        for rule in aws_vpc_security_group_ingress_rule.chat_collector_from_service :
        rule.security_group_id == module.chat_collector.sg_id && rule.from_port == 8083 && rule.to_port == 8083 && rule.ip_protocol == "tcp"
      ]) &&
      aws_vpc_security_group_ingress_rule.chat_collector_from_service["auth"].referenced_security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.chat_collector_from_service["clip"].referenced_security_group_id == module.clip.sg_id &&
      aws_vpc_security_group_ingress_rule.chat_collector_from_service["chat-detector"].referenced_security_group_id == module.chat_detector.sg_id
    )
    error_message = "chat-collector 8083 ← auth · clip · chat-detector 이그레스 · 인그레스 짝이 다르다."
  }

  assert {
    condition = (
      local.egress_targets["chat-collector"].auth.port == 8082 &&
      local.egress_targets["chat-collector"].auth.security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.auth_from_chat_collector.security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.auth_from_chat_collector.referenced_security_group_id == module.chat_collector.sg_id &&
      aws_vpc_security_group_ingress_rule.auth_from_chat_collector.from_port == 8082 &&
      aws_vpc_security_group_ingress_rule.auth_from_chat_collector.to_port == 8082
    )
    error_message = "chat-collector → auth 8082 이그레스 · auth 인그레스 ← chat-collector 짝이 없다."
  }

  assert {
    condition = (
      local.egress_targets["chat-collector"].clip.port == 8081 &&
      local.egress_targets["chat-collector"].clip.security_group_id == module.clip.sg_id &&
      local.egress_targets["chat-detector"].clip.port == 8081 &&
      local.egress_targets["chat-detector"].clip.security_group_id == module.clip.sg_id &&
      toset(keys(aws_vpc_security_group_ingress_rule.clip_from_chat)) == toset(["chat-collector", "chat-detector"]) &&
      alltrue([
        for rule in aws_vpc_security_group_ingress_rule.clip_from_chat :
        rule.security_group_id == module.clip.sg_id && rule.from_port == 8081 && rule.to_port == 8081
      ]) &&
      aws_vpc_security_group_ingress_rule.clip_from_chat["chat-collector"].referenced_security_group_id == module.chat_collector.sg_id &&
      aws_vpc_security_group_ingress_rule.clip_from_chat["chat-detector"].referenced_security_group_id == module.chat_detector.sg_id
    )
    error_message = "chat-collector · chat-detector → clip 8081 이그레스 · clip 인그레스 짝이 다르다."
  }

  # RDS ← 넷 5432(기존 auth · clip 키는 그대로).
  assert {
    condition = (
      toset(keys(aws_vpc_security_group_ingress_rule.rds_from_service)) == toset(["auth", "clip", "chat-collector", "chat-detector"]) &&
      alltrue([
        for rule in aws_vpc_security_group_ingress_rule.rds_from_service :
        rule.security_group_id == "sg-0rds" && rule.from_port == 5432 && rule.to_port == 5432
      ]) &&
      aws_vpc_security_group_ingress_rule.rds_from_service["auth"].referenced_security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.rds_from_service["clip"].referenced_security_group_id == module.clip.sg_id &&
      aws_vpc_security_group_ingress_rule.rds_from_service["chat-collector"].referenced_security_group_id == module.chat_collector.sg_id &&
      aws_vpc_security_group_ingress_rule.rds_from_service["chat-detector"].referenced_security_group_id == module.chat_detector.sg_id
    )
    error_message = "RDS SG 인그레스가 auth · clip · chat-collector · chat-detector 넷 5432 가 아니다."
  }

  # 공개 ALB 는 chat 을 모른다. Cloud Map 이름은 모듈의 name 이다.
  assert {
    condition = (
      !anytrue([
        for rule in aws_vpc_security_group_egress_rule.public_alb_to_service :
        contains([module.chat_collector.sg_id, module.chat_detector.sg_id], rule.referenced_security_group_id)
      ]) &&
      module.chat_collector.discovery_name == "chat-collector" &&
      module.chat_detector.discovery_name == "chat-detector"
    )
    error_message = "공개 ALB 가 chat SG 로 나가거나 chat Cloud Map 이름이 chat-collector · chat-detector 가 아니다."
  }
}

# --- 런타임 계약 검사 ---

run "runtime_contract_rejects_wildcard_chat_db_user" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id               = "sg-0rds"
        rds_resource_id         = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn       = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users            = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_*" }
        app_env                 = { auth = {}, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-chat-archive-example" } }
        app_secret_arns         = { auth = {}, clip = {} }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

run "runtime_contract_rejects_wildcard_chat_queue_arn" {
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
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-*.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 계약9 큐는 FIFO 다 — .fifo 가 없는 ARN 은 다른 큐다.
run "runtime_contract_rejects_non_fifo_chat_queue_arn" {
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
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# iam 에서 POSTGRES_PASSWORD 가 있으면 chat 두 서버도 부팅을 거부한다(같은
# db-support DataSourceLoginConfiguration).
run "runtime_contract_rejects_postgres_password_for_chat" {
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
          auth            = {}
          clip            = {}
          "chat-detector" = { POSTGRES_PASSWORD = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/db-password-AbCdEf" }
        }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 채팅 원본 보관 창고(PR-1 출력 chat_archive_bucket_arn)는 와일드카드 없는 버킷
# ARN 이어야 한다 — 정책 Resource 에 그대로 들어간다.
run "runtime_contract_rejects_wildcard_chat_archive_bucket" {
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
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::*"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# collector 가 쓰는 창고 이름(app_env S3_BUCKET)과 작업 역할의 창고가 같아야
# 한다 — 다르면 올리기가 권한 거부로 실패하고, 이름이 비면 아카이브가 말없이
# 꺼진다.
run "runtime_contract_rejects_chat_archive_bucket_name_mismatch" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id               = "sg-0rds"
        rds_resource_id         = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn       = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users            = { auth = "app_auth", clip = "app_clip", "chat-collector" = "app_chat_collector", "chat-detector" = "app_chat_detector" }
        app_env                 = { auth = {}, clip = {}, "chat-collector" = { S3_BUCKET = "pokeclip-prod-other" } }
        app_secret_arns         = { auth = {}, clip = {} }
        app_task_resource_arns  = { auth = {}, clip = {} }
        broadcast_queue_arns    = { clip = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-clip.fifo", chat = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-lifecycle-chat.fifo" }
        chat_archive_bucket_arn = "arn:aws:s3:::pokeclip-prod-chat-archive-example"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}
