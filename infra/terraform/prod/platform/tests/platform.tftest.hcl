# prod/platform 루트(설계 3-1 의 ⑧)를 mock provider 로 단언한다 — 계획 M8 c7
# 「c7 단언 목록」 · 「O7d 선행 0」 의 역할 경로 · 엣지 묶음 공통 지시 5 · 6 · 7
# (작업 역할 · 앱 DB IAM 인증 · auth 프록시).
#
# 앞 루트(network · certs · secrets · registry · 런타임 1단)의 상태는
# override_data 로 흉내 낸다. 계정 ID 는 가짜 12자리(111122223333), CIDR 은
# 문서용 범위(RFC 5737)다.
#
# 루트 시험은 하위 모듈 안의 자원을 주소로 볼 수 없다(terraform test). 그래서
# 모듈에 넘기는 선언(locals) · 루트 자원 · 모듈 출력을 단언한다. 리스너 기본
# 동작 고정 404 · 규칙 분할 · ARM64 같은 모듈 안 동작은 모듈 시험이 맡는다.

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

# 출력 · WAF 연결로 루트 밖에 나가는 ARN 은 자원마다 다르게 둔다(장부 8 — 위
# mock 기본값은 같은 종류끼리 모두 같아 엇갈린 연결을 가린다). 여기 없는
# 대상 그룹 · ALB · 역할은 mock 기본값(example)이라 아래 값 어느 것과도 다르다.
override_resource {
  target = module.public_alb.aws_lb.this
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/pokeclip-prod-public/000000000000b001"
  }
}

override_resource {
  target = module.internal_alb.aws_lb.this
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/pokeclip-prod-internal/000000000000b002"
  }
}

override_resource {
  target = module.nlb.aws_lb_target_group.srt
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-ingest/000000000000a001"
  }
}

override_resource {
  target = module.internal_alb.aws_lb_target_group.this["live"]
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-live/000000000000a002"
  }
}

override_resource {
  target = module.internal_alb.aws_lb_target_group.this["control"]
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-control/000000000000a003"
  }
}

override_resource {
  target = module.internal_alb.aws_lb_target_group.this["playback"]
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-playback/000000000000a004"
  }
}

override_resource {
  target = module.clip.aws_iam_role.execution
  values = {
    arn = "arn:aws:iam::111122223333:role/pokeclip/prod/pokeclip-prod-clip-execution"
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

# --- 공개 ALB(설계 4-2 · 4-3) ---

run "routes_api_auth_prefixes_from_next_config" {
  command = plan

  # web/next.config.ts 의 AUTH_API_URL 줄을 파일에서 읽어 auth 접두사와 대조한다
  # (장부 13 — 손으로 옮긴 목록이 어긋나면 그 경로가 api 호스트에서 404).
  assert {
    condition = toset(local.public_listener_rules.auth.path_prefixes) == toset(flatten(regexall(
      "source: '(/api/[^':]+)/:path\\*', target: process\\.env\\.AUTH_API_URL",
      file("${path.module}/../../../../web/next.config.ts"),
    )))
    error_message = "api 호스트 auth 규칙 접두사가 web/next.config.ts 의 AUTH_API_URL 줄(source '/api/x/:path*')과 다르다 — load_balancers.tf auth_path_prefixes 를 맞춘다."
  }

  # 위 정규식이 못 읽는 꼴의 AUTH_API_URL 줄(다른 source 모양 · 따옴표)이 생기면
  # 개수가 어긋나 여기서 멈춘다 — 대조가 조용히 줄지 않게.
  assert {
    condition = length(local.public_listener_rules.auth.path_prefixes) == length(regexall(
      "process\\.env\\.AUTH_API_URL",
      file("${path.module}/../../../../web/next.config.ts"),
    ))
    error_message = "web/next.config.ts 의 AUTH_API_URL 줄 수와 auth 접두사 수가 다르다(읽지 못한 꼴의 줄 · 중복 접두사) — 시험 정규식이나 auth_path_prefixes 를 맞춘다."
  }

  assert {
    condition = (
      local.public_listener_rules.auth.target_group == "auth" &&
      local.public_listener_rules.auth.host_headers == ["api.pokeclip.com"] &&
      length(local.public_listener_rules.auth.path_patterns) == 0
    )
    error_message = "auth 규칙이 api 호스트 → auth 대상 그룹(경로 = 접두사만)이 아니다."
  }

  assert {
    condition = alltrue([
      for prefix in local.public_listener_rules.auth.path_prefixes : !startswith(prefix, "/api/clip")
    ])
    error_message = "auth 규칙에 /api/clip 으로 시작하는 접두사가 있다."
  }

  assert {
    condition = (
      local.public_listener_rules["clip-sse"].path_patterns == ["/api/clip/broadcasts/*/events"] &&
      local.public_listener_rules["clip-sse"].target_group == "clip-sse" &&
      local.public_listener_rules.clip.path_patterns == ["/api/clip/*"] &&
      local.public_listener_rules.clip.target_group == "clip" &&
      local.public_listener_rules["clip-sse"].host_headers == ["api.pokeclip.com"] &&
      local.public_listener_rules.clip.host_headers == ["api.pokeclip.com"]
    )
    error_message = "clip-sse · clip 규칙의 호스트 · 경로 · 대상 그룹이 설계 4-3 과 다르다."
  }

  # auth 규칙은 접두사마다 하나로 나뉘어 priority + 0 … priority + 8 을 쓴다
  # (alb 모듈 max_path_values_per_rule 기본 2).
  assert {
    condition = (
      local.public_listener_rules.auth.priority + length(local.public_listener_rules.auth.path_prefixes) - 1 <
      local.public_listener_rules["clip-sse"].priority &&
      local.public_listener_rules["clip-sse"].priority < local.public_listener_rules.clip.priority
    )
    error_message = "우선순위가 auth 규칙 전부 < clip-sse < clip 이 아니다."
  }
}

run "routes_app_host_to_next_only" {
  command = plan

  assert {
    condition = (
      local.public_listener_rules.next.host_headers == ["app.pokeclip.com"] &&
      local.public_listener_rules.next.target_group == "next" &&
      length(local.public_listener_rules.next.path_prefixes) == 0 &&
      length(local.public_listener_rules.next.path_patterns) == 0
    )
    error_message = "app 호스트 규칙이 경로 조건 없이 next 하나로 가지 않는다."
  }

  assert {
    condition     = toset(keys(local.public_listener_rules)) == toset(["auth", "clip-sse", "clip", "next"])
    error_message = "공개 ALB 규칙 키가 auth · clip-sse · clip · next 와 다르다."
  }

  assert {
    condition     = local.alb_idle_timeout == 60
    error_message = "공개 ALB idle_timeout 이 60 이 아니다."
  }

  # 요청 밀수(request smuggling)에 쓰이는 잘못된 헤더를 공개 ALB 가 버린다.
  assert {
    condition     = local.public_alb_drop_invalid_header_fields == true
    error_message = "공개 ALB drop_invalid_header_fields 가 true 가 아니다."
  }
}

run "public_target_groups_are_services_only" {
  command = plan

  assert {
    condition     = toset(keys(local.public_target_groups)) == toset(["next", "auth", "clip", "clip-sse"])
    error_message = "공개 ALB 대상 그룹 키가 next · auth · clip · clip-sse 와 다르다(Media 용이 섞였거나 빠짐)."
  }

  assert {
    condition = (
      local.public_target_groups.next.port == 3000 &&
      local.public_target_groups.next.health_check.path == "/api/ping" &&
      local.public_target_groups.auth.port == 8082 &&
      local.public_target_groups.auth.health_check.path == "/actuator/health" &&
      local.public_target_groups.clip.port == 8081 &&
      local.public_target_groups.clip.health_check.path == "/actuator/health" &&
      local.public_target_groups["clip-sse"].port == 8081 &&
      local.public_target_groups["clip-sse"].health_check.path == "/actuator/health"
    )
    error_message = "공개 대상 그룹의 포트 · 헬스 경로가 next 3000 /api/ping · auth 8082 · clip 8081 /actuator/health 와 다르다."
  }

  assert {
    condition = alltrue([
      for group in local.public_target_groups :
      group.protocol == "HTTP" && group.target_type == "ip" && group.health_check.port == "traffic-port"
    ])
    error_message = "공개 대상 그룹이 HTTP · ip 대상 · traffic-port 헬스가 아니다."
  }
}

# --- 내부 ALB · Media 대상 그룹(설계 4-4 · 4-5 · 3-3-1) ---

run "internal_alb_routes_media_paths" {
  command = plan

  assert {
    condition = (
      local.internal_listener_rules.live.path_patterns == ["/live/*"] &&
      local.internal_listener_rules.live.target_group == "live" &&
      local.internal_listener_rules.live.path_rewrite.regex == "^/live/(.*)$" &&
      local.internal_listener_rules.live.path_rewrite.replace == "/$1"
    )
    error_message = "내부 ALB /live/* → live(transform ^/live/(.*)$ → /$1) 규칙이 다르다."
  }

  assert {
    condition = (
      local.internal_listener_rules.rewind.path_patterns == ["/api/streams/*/rewind"] &&
      local.internal_listener_rules.rewind.target_group == "control" &&
      local.internal_listener_rules.rewind.path_rewrite == null &&
      local.internal_listener_rules.ingest.path_patterns == ["/api/ingest/*"] &&
      local.internal_listener_rules.ingest.target_group == "control" &&
      local.internal_listener_rules.ingest.path_rewrite == null
    )
    error_message = "내부 ALB 제어면 규칙(/api/streams/*/rewind · /api/ingest/* → control, 재작성 없음)이 다르다."
  }

  assert {
    condition = (
      local.internal_listener_rules.playback.path_patterns == ["/playback/*"] &&
      local.internal_listener_rules.playback.target_group == "playback" &&
      local.internal_listener_rules.playback.path_rewrite.regex == "^/playback/(.*)$" &&
      local.internal_listener_rules.playback.path_rewrite.replace == "/$1"
    )
    error_message = "내부 ALB /playback/* → playback(transform ^/playback/(.*)$ → /$1) 규칙이 다르다."
  }

  assert {
    condition     = toset(keys(local.internal_listener_rules)) == toset(["live", "rewind", "ingest", "playback"])
    error_message = "내부 ALB 규칙 키가 live · rewind · ingest · playback 과 다르다."
  }
}

run "media_target_groups_check_proxy_health_port" {
  command = plan

  # media 루트는 이 두 출력을 remote_state 로 읽어 nginx 를 띄운다 — 대상 그룹과
  # 상자가 한 값에서 나온다.
  assert {
    condition = (
      output.media_proxy_ports == tomap({ live = 28181, playback = 28182, control = 28183, health = 28184 }) &&
      output.media_health_path == "/example-proxy-health"
    )
    error_message = "출력 media_proxy_ports · media_health_path 가 입력과 다르다."
  }

  assert {
    condition = alltrue([
      for name, group in local.internal_target_groups :
      group.port == output.media_proxy_ports[name] &&
      group.health_check.port == tostring(output.media_proxy_ports["health"]) &&
      group.health_check.path == output.media_health_path
    ])
    error_message = "Media 대상 그룹 포트 · 헬스가 출력 media_proxy_ports · media_health_path 에서 나오지 않는다."
  }

  assert {
    condition     = toset(keys(local.internal_target_groups)) == toset(["live", "control", "playback"])
    error_message = "내부 ALB 대상 그룹 키가 live · control · playback 과 다르다."
  }

  assert {
    condition = (
      local.internal_target_groups.live.port == 28181 &&
      local.internal_target_groups.playback.port == 28182 &&
      local.internal_target_groups.control.port == 28183
    )
    error_message = "Media 대상 그룹 포트가 proxy_ports 의 live · playback · control 과 다르다."
  }

  # 헬스 = 프록시 헬스 포트 · nginx 헬스 경로(traffic-port 아님) — 제어면 서버가
  # 없어도 control 이 healthy 다(장부 r4 U2 · 설계 3-3-1).
  assert {
    condition = alltrue([
      for group in local.internal_target_groups :
      group.protocol == "HTTP" && group.target_type == "instance" &&
      group.health_check.port == "28184" && group.health_check.path == "/example-proxy-health"
    ])
    error_message = "Media 대상 그룹이 HTTP · instance · 헬스(포트 = proxy_ports.health, 경로 = media_health_path)가 아니다."
  }
}

# media 루트는 service_ports.auth 로 Media → auth(4C resolve) SG 짝의 포트를
# 정한다(kty 결정 2026-10-25 (나)) — auth 서비스 포트와 한 값에서 나온다.
run "outputs_service_ports_from_services" {
  command = plan

  variables {
    services = {
      next = { port = 13000, health_check_path = "/api/ping", cpu = 512, memory = 1024, health_check_grace = 60 }
      auth = { port = 18082, health_check_path = "/actuator/health", cpu = 1024, memory = 2048, health_check_grace = 180 }
      clip = { port = 18081, health_check_path = "/actuator/health", cpu = 1024, memory = 2048, health_check_grace = 180 }
    }
  }

  assert {
    condition     = output.service_ports == { next = 13000, auth = 18082, clip = 18081 }
    error_message = "출력 service_ports 가 services 의 포트(next · auth · clip)와 다르다."
  }

  assert {
    condition     = output.service_ports.auth == aws_vpc_security_group_ingress_rule.auth_from_clip.from_port
    error_message = "출력 service_ports.auth 가 auth SG 인그레스(clip → auth resolve)의 포트와 다르다."
  }
}

# 내부 ALB 리스너 인증서 · NLB 위치 · 출처, 출력 계약. 계산 속성이라 apply 로
# 돌린다.
run "lb_outputs_match_consumer_contract" {
  command = apply

  assert {
    condition = (
      output.public_alb_arn == module.public_alb.alb_arn &&
      output.public_alb_dns_name == module.public_alb.dns_name &&
      output.public_alb_zone_id == module.public_alb.zone_id &&
      output.public_alb_sg_id == module.public_alb.sg_id &&
      output.internal_alb_arn == module.internal_alb.alb_arn &&
      output.internal_alb_dns_name == module.internal_alb.dns_name &&
      output.internal_alb_sg_id == module.internal_alb.sg_id
    )
    error_message = "출력 public_alb_* · internal_alb_* 이 모듈 출력과 다르다."
  }

  assert {
    condition = (
      output.nlb_dns_name == module.nlb.nlb_dns_name &&
      output.nlb_zone_id == module.nlb.nlb_zone_id &&
      output.nlb_sg_id == module.nlb.nlb_sg_id
    )
    error_message = "출력 nlb_* 이 모듈 출력과 다르다."
  }

  assert {
    condition = (
      toset(keys(output.target_group_arns)) == toset(["ingest", "live", "control", "playback"]) &&
      output.target_group_arns.ingest == module.nlb.target_group_arn &&
      output.target_group_arns.live == module.internal_alb.target_group_arns["live"] &&
      output.target_group_arns.control == module.internal_alb.target_group_arns["control"] &&
      output.target_group_arns.playback == module.internal_alb.target_group_arns["playback"]
    )
    error_message = "출력 target_group_arns 가 Media 대상 그룹 넷(ingest · live · control · playback)과 다르다."
  }

  assert {
    condition = (
      output.ecs_cluster_arn == aws_ecs_cluster.this.arn &&
      output.discovery_namespace_id == aws_service_discovery_private_dns_namespace.this.id &&
      output.clip_execution_role_arn == module.clip.execution_role_arn &&
      output.service_sg_ids == {
        auth = module.auth.sg_id
        clip = module.clip.sg_id
        next = module.next.sg_id
      }
    )
    error_message = "출력 ecs_cluster_arn · discovery_namespace_id · clip_execution_role_arn · service_sg_ids 가 자원 · 모듈 출력과 다르다."
  }
}

# 소비 루트가 읽는 출력이 맞는 자원을 가리키는지 값으로 본다(장부 8). 위
# override_resource 로 자원마다 ARN 이 달라, 출력을 엇갈려 이으면(예: live 에
# 공개 ALB 의 auth 대상 그룹) 여기서 걸린다.
run "boundary_outputs_point_at_distinct_resources" {
  command = apply

  assert {
    condition = output.target_group_arns == {
      ingest   = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-ingest/000000000000a001"
      live     = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-live/000000000000a002"
      control  = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-control/000000000000a003"
      playback = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-playback/000000000000a004"
    }
    error_message = "출력 target_group_arns 가 NLB ingest · 내부 ALB live · control · playback 대상 그룹을 저마다 가리키지 않는다."
  }

  assert {
    condition = (
      output.public_alb_arn == "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/pokeclip-prod-public/000000000000b001" &&
      output.internal_alb_arn == "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/pokeclip-prod-internal/000000000000b002" &&
      aws_wafv2_web_acl_association.public.resource_arn == output.public_alb_arn
    )
    error_message = "출력 public_alb_arn · internal_alb_arn 이나 WAF 연결 대상이 공개 · 내부 ALB 를 저마다 가리키지 않는다."
  }

  assert {
    condition     = output.clip_execution_role_arn == "arn:aws:iam::111122223333:role/pokeclip/prod/pokeclip-prod-clip-execution"
    error_message = "출력 clip_execution_role_arn 이 clip 실행 역할이 아니다(작업 역할 · 다른 서비스 역할)."
  }

  # SG id 는 mock 이 자원마다 무작위로 만든다 — 엇갈림을 잡으려면 서로 달라야
  # 한다(전제 확인).
  assert {
    condition = length(distinct([
      output.service_sg_ids.next,
      output.service_sg_ids.auth,
      output.service_sg_ids.clip,
      output.public_alb_sg_id,
      output.internal_alb_sg_id,
      output.nlb_sg_id,
      "sg-0rds",
    ])) == 7
    error_message = "서비스 · ALB · NLB · RDS SG id 가 서로 다르지 않아 SG 엇갈림을 시험이 못 잡는다."
  }
}

run "nlb_sits_in_media_public_subnet_for_team_ips" {
  command = plan

  assert {
    condition     = local.nlb_subnet_id == "subnet-0pubc"
    error_message = "NLB 서브넷이 network 의 media_public_subnet_id 가 아니다."
  }

  assert {
    condition     = local.nlb_health_port == 28184
    error_message = "NLB 헬스 포트가 proxy_ports.health 가 아니다."
  }
}

# --- WAF · Cloud Map ---

run "attaches_regional_waf_to_public_alb" {
  command = apply

  assert {
    condition = (
      aws_wafv2_web_acl_association.public.resource_arn == module.public_alb.alb_arn &&
      aws_wafv2_web_acl_association.public.web_acl_arn == module.waf.web_acl_arn
    )
    error_message = "REGIONAL WAF 가 공개 ALB 에 붙지 않는다."
  }

  # CommonRuleSet 은 본문 8KB 규칙이 프로필 사진 업로드를 막을 수 있어 기본에서
  # 뺀다.
  assert {
    condition = (
      jsonencode(var.waf_managed_rule_groups) == jsonencode(["AWSManagedRulesKnownBadInputsRuleSet", "AWSManagedRulesAmazonIpReputationList"]) &&
      var.waf_rate_limit == 2000
    )
    error_message = "WAF 기본값이 KnownBadInputs · AmazonIpReputationList · 속도 2000 이 아니다."
  }
}

run "registers_cloud_map_namespace_in_prod_vpc" {
  command = plan

  assert {
    condition = (
      aws_service_discovery_private_dns_namespace.this.name == "pokeclip.internal" &&
      aws_service_discovery_private_dns_namespace.this.vpc == "vpc-0example"
    )
    error_message = "Cloud Map 네임스페이스가 prod VPC 의 pokeclip.internal 이 아니다."
  }

  assert {
    condition     = aws_ecs_cluster.this.name == "pokeclip-prod"
    error_message = "ECS 클러스터 이름이 pokeclip-prod 가 아니다."
  }
}
