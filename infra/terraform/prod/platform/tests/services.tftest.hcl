# prod/platform 루트의 ECS 서비스 쪽을 mock provider 로 단언한다 — 계획 M8 c7
# 「c7 단언 목록」 의 SG 규칙 · clip env · CORS 부재, 「O7d 선행 0」 의 역할 경로,
# 엣지 묶음 공통 지시 5(작업 역할 = 코드가 부르는 동작만) · 6(앱 DB IAM 인증) ·
# 7(auth 프록시) · 런타임 계약 검사.
#
# 앞 루트 상태 흉내 · 가짜 값은 platform.tftest.hcl 과 같다(시험 파일마다
# 따로 둬야 한다). 루트 시험은 하위 모듈 안의 자원을 볼 수 없어 모듈에 넘기는
# 선언(locals) · 루트 자원 · 모듈 출력을 단언한다.

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
        next = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/next"
        auth = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/auth"
        clip = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/clip"
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
        auth = "app_auth"
        clip = "app_clip"
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
    }
  }
}

variables {
  image_digests = {
    next = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    auth = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
    clip = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
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


# --- 이미지 · 크기 · 역할 경로 ---

run "services_pin_digests_sizes_and_prod_iam_path" {
  command = plan

  assert {
    condition = (
      local.service_images.next == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/next@sha256:1111111111111111111111111111111111111111111111111111111111111111" &&
      local.service_images.auth == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/auth@sha256:2222222222222222222222222222222222222222222222222222222222222222" &&
      local.service_images.clip == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/clip@sha256:3333333333333333333333333333333333333333333333333333333333333333"
    )
    error_message = "서비스 이미지가 registry repo_urls + 다이제스트가 아니다."
  }

  # 설계 10절: next 0.5 vCPU · 1 GB, auth · clip 각 1 vCPU · 2 GB. 기동 유예는
  # Flyway 를 도는 auth · clip 이 더 길다.
  assert {
    condition = (
      var.services.next.cpu == 512 && var.services.next.memory == 1024 && var.services.next.health_check_grace == 60 &&
      var.services.auth.cpu == 1024 && var.services.auth.memory == 2048 && var.services.auth.health_check_grace == 180 &&
      var.services.clip.cpu == 1024 && var.services.clip.memory == 2048 && var.services.clip.health_check_grace == 180
    )
    error_message = "서비스 크기 · 기동 유예 기본값이 설계 10절(next 512/1024 · 60초, auth · clip 1024/2048 · 180초)과 다르다."
  }

  # 실행 · 작업 역할 경로 = 2-3 판정의 prod 주체 식별 기준(계획 3절).
  assert {
    condition     = local.iam_path == "/pokeclip/prod/" && local.log_retention_days == 30
    error_message = "ECS 실행 · 작업 역할 경로가 /pokeclip/prod/ 가 아니거나 로그 보존이 30일이 아니다."
  }
}

# --- env · 비밀 ---

run "clip_env_carries_playback_signing_values" {
  command = plan

  assert {
    condition = (
      local.service_env.clip["CLOUDFRONT_KEY_PAIR_ID"] == "K0EXAMPLEKEYID" &&
      local.service_env.clip["MEDIA_BASE_URL"] == "https://media.pokeclip.com" &&
      local.service_env.clip["PLAYBACK_COOKIE_DOMAIN"] == ".pokeclip.com"
    )
    error_message = "clip env 의 CLOUDFRONT_KEY_PAIR_ID · MEDIA_BASE_URL · PLAYBACK_COOKIE_DOMAIN 이 public_key_id · 운영 값과 다르다."
  }

  # clip → auth 내부 resolve 는 Cloud Map 이름으로 간다(SG 짝은 아래 run).
  assert {
    condition     = local.service_env.clip["AUTH_BASE_URL"] == "http://auth.pokeclip.internal:8082"
    error_message = "clip AUTH_BASE_URL 이 auth 의 Cloud Map 이름 · 포트가 아니다."
  }

  # 셋 중 하나라도 빠지면 부팅을 거부한다(POK-321 — prod 정본).
  assert {
    condition     = local.service_env.clip["PLAYBACK_REQUIRED"] == "true"
    error_message = "clip PLAYBACK_REQUIRED 가 true 가 아니다."
  }

  assert {
    condition = (
      local.service_env.clip["BROADCAST_QUEUE_URL"] == "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-broadcast-clip" &&
      local.service_env.auth["DB_HOST"] == "db.example.invalid"
    )
    error_message = "런타임 app_env 가 태스크 env 로 넘어가지 않는다."
  }

  assert {
    condition = local.service_secrets.clip == {
      CLOUDFRONT_PRIVATE_KEY_PEM = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/signing-key-AbCdEf"
      JWT_SECRET                 = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
      INTERNAL_API_TOKEN         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
    }
    error_message = "clip secrets 가 CLOUDFRONT_PRIVATE_KEY_PEM(signing_key_secret_arn) + 런타임 app_secret_arns.clip 이 아니다."
  }

  assert {
    condition = (
      local.service_secrets.auth == data.terraform_remote_state.runtime.outputs.app_secret_arns.auth &&
      length(local.service_secrets.next) == 0
    )
    error_message = "auth secrets 가 런타임 app_secret_arns.auth 와 다르거나 next 에 비밀이 있다."
  }
}

# 브라우저는 api 를 부르지 않는다(설계 4-3 · ADR-089 ③). 그래도 auth · clip 은
# 허용 출처가 비면 부팅하지 않아(CorsProperties @NotEmpty) app 오리진 하나만
# 둔다(kty 확정).
run "cors_allows_app_origin_only" {
  command = plan

  assert {
    condition = alltrue([
      for service in ["auth", "clip"] : local.service_env[service]["CORS_ALLOWED_ORIGINS"] == "https://app.pokeclip.com"
    ])
    error_message = "auth · clip env CORS_ALLOWED_ORIGINS 가 https://app.pokeclip.com 하나가 아니다."
  }
}

run "auth_trusts_forwarded_headers_natively" {
  command = plan

  assert {
    condition     = local.service_env.auth["FORWARD_HEADERS_STRATEGY"] == "native"
    error_message = "auth FORWARD_HEADERS_STRATEGY 가 native 가 아니다."
  }

  # 신뢰 대역 = network vpc_cidr(가짜 192.0.2.0/24) — ALB · next 가 VPC 안이다.
  # auth 는 빈 값을 「아무 프록시도 안 믿음」 으로 읽는다(POK-316).
  assert {
    condition     = try(local.service_env.auth["FORWARD_HEADERS_TRUSTED_PROXIES"], "") == "192.0.2.0/24"
    error_message = "auth FORWARD_HEADERS_TRUSTED_PROXIES 가 network vpc_cidr 가 아니다."
  }

  # 옛 이름(톰캣 속성 직접 지정)은 쓰지 않는다 — POK-316 계약 이름은 위 하나다.
  assert {
    condition     = !contains(keys(local.service_env.auth), "SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES")
    error_message = "auth 에 옛 신뢰 대역 env(SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES)가 남아 있다."
  }

  # 탈퇴 정리(withdrawal-purge)가 부르는 주소 — 비면 auth 가 부팅하지 않는다.
  assert {
    condition = (
      local.service_env.auth["CLIP_BASE_URL"] == "http://clip.pokeclip.internal:8081" &&
      local.service_env.auth["COLLECTOR_BASE_URL"] == "http://chat-collector.pokeclip.internal:8083"
    )
    error_message = "auth CLIP_BASE_URL · COLLECTOR_BASE_URL 이 clip · chat-collector 의 Cloud Map 이름 · 포트가 아니다."
  }

  # prod 는 처음부터 Secrets Manager — 접두 · StreamKeySecrets 정책과 짝이다
  # (application.yml 166–169 의 기본값은 postgres).
  assert {
    condition = (
      local.service_env.auth["STREAM_KEY_SECRET_STORE_TYPE"] == "aws" &&
      local.service_env.auth["STREAM_KEY_SECRET_RETIRE_FROM"] == "aws"
    )
    error_message = "auth STREAM_KEY_SECRET_STORE_TYPE · STREAM_KEY_SECRET_RETIRE_FROM 이 aws 가 아니다."
  }

  assert {
    condition     = local.service_env.auth["STREAM_KEY_SECRET_REF_PREFIX"] == "pokeclip/prod/stream-key/"
    error_message = "auth STREAM_KEY_SECRET_REF_PREFIX 가 작업 역할 정책의 접두와 다르다."
  }
}

# 런타임 app_env 가 같은 이름을 다른 값으로 줘도 platform 값이 이긴다.
run "platform_stream_key_env_wins_over_runtime_app_env" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id         = "sg-0rds"
        rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users      = { auth = "app_auth", clip = "app_clip" }
        app_env = {
          auth = {
            STREAM_KEY_SECRET_STORE_TYPE  = "postgres"
            STREAM_KEY_SECRET_RETIRE_FROM = "postgres"
            STREAM_KEY_SECRET_REF_PREFIX  = "pokeclip/other/"
          }
          clip = {}
        }
        app_secret_arns        = { auth = {}, clip = {} }
        app_task_resource_arns = { auth = {}, clip = {} }
      }
    }
  }

  assert {
    condition = (
      local.service_env.auth["STREAM_KEY_SECRET_STORE_TYPE"] == "aws" &&
      local.service_env.auth["STREAM_KEY_SECRET_RETIRE_FROM"] == "aws" &&
      local.service_env.auth["STREAM_KEY_SECRET_REF_PREFIX"] == "pokeclip/prod/stream-key/"
    )
    error_message = "런타임 app_env 의 스트림 키 저장소 env 가 platform 값을 덮었다."
  }
}

# platform 루트가 공개 승인 변수를 media_ingest 에 넘긴다(기본 false).
run "rejects_public_ingest_without_approval" {
  command = plan

  variables {
    ingest_allowed_cidrs = ["0.0.0.0/0"]
  }

  expect_failures = [var.ingest_allowed_cidrs]
}

# --- 앱 DB IAM 인증(POK-407) ---

run "iam_db_auth_by_default" {
  command = plan

  assert {
    condition = alltrue([
      for service in ["auth", "clip"] :
      local.service_env[service]["POSTGRES_AUTH"] == "iam" &&
      local.service_env[service]["POSTGRES_SSLROOTCERT"] == "/etc/pokeclip/rds-ca/global-bundle.pem"
    ])
    error_message = "기본값(iam)에서 auth · clip env 에 POSTGRES_AUTH=iam · POSTGRES_SSLROOTCERT 가 없다."
  }

  assert {
    condition = (
      local.service_env.auth["POSTGRES_USER"] == "app_auth" &&
      local.service_env.clip["POSTGRES_USER"] == "app_clip"
    )
    error_message = "POSTGRES_USER 가 런타임 app_db_users 의 사용자와 다르다."
  }

  # 자기 DB 사용자 하나로만 접속한다(와일드카드 없음).
  assert {
    condition = (
      jsonencode({ for statement in local.task_role_statements.auth : statement.sid => statement }["DbConnect"]) == jsonencode({
        sid       = "DbConnect"
        actions   = ["rds-db:connect"]
        resources = ["arn:aws:rds-db:ap-northeast-2:111122223333:dbuser:db-ABCDEFGHIJKLMNOPQRSTUVWXYZ/app_auth"]
      }) &&
      jsonencode({ for statement in local.task_role_statements.clip : statement.sid => statement }["DbConnect"]) == jsonencode({
        sid       = "DbConnect"
        actions   = ["rds-db:connect"]
        resources = ["arn:aws:rds-db:ap-northeast-2:111122223333:dbuser:db-ABCDEFGHIJKLMNOPQRSTUVWXYZ/app_clip"]
      })
    )
    error_message = "DbConnect 문장이 rds-db:connect · dbuser:<rds_resource_id>/<자기 사용자> 하나가 아니다."
  }
}

run "password_db_auth_drops_rds_db_connect" {
  command = plan

  variables {
    app_db_auth = "password"
  }

  assert {
    condition = alltrue([
      for service in ["auth", "clip"] :
      !contains(keys(local.service_env[service]), "POSTGRES_AUTH") &&
      !contains(keys(local.service_env[service]), "POSTGRES_SSLROOTCERT")
    ])
    error_message = "password 인데 POSTGRES_AUTH · POSTGRES_SSLROOTCERT env 가 남았다."
  }

  assert {
    condition = !anytrue(flatten([
      for service in ["auth", "clip"] : [
        for statement in local.task_role_statements[service] : contains(statement.actions, "rds-db:connect")
      ]
    ]))
    error_message = "password 인데 rds-db:connect 문장이 남았다."
  }
}

# --- 작업 역할(코드가 부르는 동작만 — 공통 지시 5) ---

run "auth_task_role_allows_stream_key_secrets_and_photos" {
  command = plan

  # SecretsManagerSecretStore.java:86 · 93 · 113 · 140 — 이름은 접두 + UUID 라
  # 접두 와일드카드만 쓴다.
  assert {
    condition = jsonencode({ for statement in local.task_role_statements.auth : statement.sid => statement }["StreamKeySecrets"]) == jsonencode({
      sid       = "StreamKeySecrets"
      actions   = ["secretsmanager:CreateSecret", "secretsmanager:DeleteSecret", "secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
      resources = ["arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/stream-key/*"]
    })
    error_message = "auth 스트림 키 비밀 문장이 Create · Delete · Get · Put 네 동작 · 접두 와일드카드가 아니다."
  }

  # S3PhotoStorage.java:38 · 66 · 94.
  assert {
    condition = jsonencode({ for statement in local.task_role_statements.auth : statement.sid => statement }["ProfilePhotoObjects"]) == jsonencode({
      sid       = "ProfilePhotoObjects"
      actions   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
      resources = ["arn:aws:s3:::pokeclip-prod-profile-photo-example/*"]
    })
    error_message = "auth 프로필 사진 문장이 Delete · Get · Put Object · 버킷/* 가 아니다."
  }

  assert {
    condition     = toset([for statement in local.task_role_statements.auth : statement.sid]) == toset(["StreamKeySecrets", "ProfilePhotoObjects", "DbConnect"])
    error_message = "auth 작업 역할 문장이 StreamKeySecrets · ProfilePhotoObjects · DbConnect 밖에도 있다."
  }
}

run "clip_task_role_allows_queues_and_buckets" {
  command = plan

  assert {
    condition = (
      jsonencode({ for statement in local.task_role_statements.clip : statement.sid => statement }["BroadcastIntake"]) == jsonencode({
        sid       = "BroadcastIntake"
        actions   = ["sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = ["arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-broadcast-clip"]
      }) &&
      jsonencode({ for statement in local.task_role_statements.clip : statement.sid => statement }["OrderQueues"]) == jsonencode({
        sid     = "OrderQueues"
        actions = ["sqs:SendMessage"]
        resources = [
          "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-render",
          "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-thumbnail",
          "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-upload",
        ]
      }) &&
      jsonencode({ for statement in local.task_role_statements.clip : statement.sid => statement }["DeadLetterQueues"]) == jsonencode({
        sid     = "DeadLetterQueues"
        actions = ["sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = [
          "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-render-dlq",
          "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-upload-dlq",
        ]
      })
    )
    error_message = "clip 큐 문장(방송 수신 · 주문 송신 · DLQ 수신)이 코드가 부르는 동작 · 큐와 다르다."
  }

  assert {
    condition = (
      jsonencode({ for statement in local.task_role_statements.clip : statement.sid => statement }["ClipObjects"]) == jsonencode({
        sid       = "ClipObjects"
        actions   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        resources = ["arn:aws:s3:::pokeclip-prod-clips-example/*"]
      }) &&
      jsonencode({ for statement in local.task_role_statements.clip : statement.sid => statement }["ClipList"]) == jsonencode({
        sid       = "ClipList"
        actions   = ["s3:ListBucket"]
        resources = ["arn:aws:s3:::pokeclip-prod-clips-example"]
      }) &&
      jsonencode({ for statement in local.task_role_statements.clip : statement.sid => statement }["SegmentDelete"]) == jsonencode({
        sid       = "SegmentDelete"
        actions   = ["s3:DeleteObject"]
        resources = ["arn:aws:s3:::pokeclip-prod-rewind-example/*"]
      })
    )
    error_message = "clip S3 문장(클립 창고 읽기 · 쓰기 · 지우기 · 목록, 조각 창고 지우기)이 다르다."
  }

  assert {
    condition = length(local.task_role_statements.next) == 0 && !anytrue(flatten([
      for service in ["auth", "clip"] : [
        for statement in local.task_role_statements[service] : contains(statement.resources, "*")
      ]
    ]))
    error_message = "next 에 작업 역할 문장이 있거나 어떤 문장의 Resource 가 맨 * 다."
  }
}

# 런타임이 내지 않은 자원(꺼진 기능)의 문장은 만들지 않는다.
run "task_role_omits_resources_runtime_does_not_expose" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id         = "sg-0rds"
        rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users      = { auth = "app_auth", clip = "app_clip" }
        app_env           = { auth = {}, clip = {} }
        app_secret_arns   = { auth = {}, clip = {} }
        app_task_resource_arns = {
          auth = {}
          clip = { clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example" }
        }
      }
    }
  }

  assert {
    condition = (
      toset([for statement in local.task_role_statements.auth : statement.sid]) == toset(["StreamKeySecrets", "DbConnect"]) &&
      toset([for statement in local.task_role_statements.clip : statement.sid]) == toset(["ClipObjects", "ClipList", "DbConnect"])
    )
    error_message = "런타임이 내지 않은 자원의 작업 역할 문장이 만들어졌다."
  }
}

# --- SG 규칙(설계 5-5) — SG id 가 계산 속성이라 apply ---

run "security_group_rules_pair_services_lb_and_rds" {
  command = apply

  # RDS ← auth · clip 5432 만(Media ↔ RDS 는 media 루트 몫 — M9).
  assert {
    condition = (
      toset(keys(aws_vpc_security_group_ingress_rule.rds_from_service)) == toset(["auth", "clip"]) &&
      alltrue([
        for key, rule in aws_vpc_security_group_ingress_rule.rds_from_service :
        rule.security_group_id == "sg-0rds" && rule.from_port == 5432 && rule.to_port == 5432 && rule.ip_protocol == "tcp"
      ]) &&
      aws_vpc_security_group_ingress_rule.rds_from_service["auth"].referenced_security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.rds_from_service["clip"].referenced_security_group_id == module.clip.sg_id
    )
    error_message = "RDS SG 인그레스가 auth · clip SG 5432 두 개가 아니다."
  }

  assert {
    condition = alltrue([
      for service in ["auth", "clip"] :
      local.egress_targets[service].rds.port == 5432 && local.egress_targets[service].rds.security_group_id == "sg-0rds"
    ]) && !contains(keys(local.egress_targets.next), "rds")
    error_message = "auth · clip 이그레스 → RDS 5432 가 없거나 next 에 RDS 이그레스가 있다."
  }

  # clip → auth 8082 짝(자격 판정 — 없으면 쿠키 발급 · SSE 가 503).
  assert {
    condition = (
      local.egress_targets.clip.auth.port == 8082 &&
      local.egress_targets.clip.auth.security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.auth_from_clip.security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.auth_from_clip.referenced_security_group_id == module.clip.sg_id &&
      aws_vpc_security_group_ingress_rule.auth_from_clip.from_port == 8082
    )
    error_message = "clip → auth 8082 이그레스 · auth 인그레스 ← clip 짝이 없다."
  }

  # auth → clip 8081 짝(탈퇴 정리). auth → chat-collector 는 런타임 2단 몫.
  assert {
    condition = (
      local.egress_targets.auth.clip.port == 8081 &&
      local.egress_targets.auth.clip.security_group_id == module.clip.sg_id &&
      aws_vpc_security_group_ingress_rule.clip_from_auth.security_group_id == module.clip.sg_id &&
      aws_vpc_security_group_ingress_rule.clip_from_auth.referenced_security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.clip_from_auth.from_port == 8081
    )
    error_message = "auth → clip 8081 이그레스 · clip 인그레스 ← auth 짝이 없다."
  }

  # Next rewrite → auth · clip(Cloud Map).
  assert {
    condition = (
      local.egress_targets.next.auth.port == 8082 &&
      local.egress_targets.next.auth.security_group_id == module.auth.sg_id &&
      local.egress_targets.next.clip.port == 8081 &&
      local.egress_targets.next.clip.security_group_id == module.clip.sg_id &&
      aws_vpc_security_group_ingress_rule.from_next["auth"].security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.from_next["auth"].from_port == 8082 &&
      aws_vpc_security_group_ingress_rule.from_next["clip"].security_group_id == module.clip.sg_id &&
      aws_vpc_security_group_ingress_rule.from_next["clip"].from_port == 8081 &&
      alltrue([for rule in aws_vpc_security_group_ingress_rule.from_next : rule.referenced_security_group_id == module.next.sg_id])
    )
    error_message = "next → auth 8082 · clip 8081 이그레스와 인그레스 짝이 다르다."
  }

  # 공개 ALB: 인 443 ← 0.0.0.0/0, 아웃 → next · auth · clip(서비스 = 헬스 포트).
  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.public_alb_https.security_group_id == module.public_alb.sg_id &&
      aws_vpc_security_group_ingress_rule.public_alb_https.cidr_ipv4 == "0.0.0.0/0" &&
      aws_vpc_security_group_ingress_rule.public_alb_https.from_port == 443 &&
      toset(keys(aws_vpc_security_group_egress_rule.public_alb_to_service)) == toset(["next", "auth", "clip"]) &&
      aws_vpc_security_group_egress_rule.public_alb_to_service["next"].referenced_security_group_id == module.next.sg_id &&
      aws_vpc_security_group_egress_rule.public_alb_to_service["next"].from_port == 3000 &&
      aws_vpc_security_group_egress_rule.public_alb_to_service["auth"].referenced_security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_egress_rule.public_alb_to_service["auth"].from_port == 8082 &&
      aws_vpc_security_group_egress_rule.public_alb_to_service["clip"].referenced_security_group_id == module.clip.sg_id &&
      aws_vpc_security_group_egress_rule.public_alb_to_service["clip"].from_port == 8081 &&
      alltrue([for rule in aws_vpc_security_group_egress_rule.public_alb_to_service : rule.security_group_id == module.public_alb.sg_id])
    )
    error_message = "공개 ALB 인그레스 443 ← 0.0.0.0/0 · 이그레스 → next 3000 · auth 8082 · clip 8081 이 다르다."
  }

  assert {
    condition = (
      toset(keys(aws_vpc_security_group_ingress_rule.service_from_public_alb)) == toset(["next", "auth", "clip"]) &&
      alltrue([for rule in aws_vpc_security_group_ingress_rule.service_from_public_alb : rule.referenced_security_group_id == module.public_alb.sg_id]) &&
      aws_vpc_security_group_ingress_rule.service_from_public_alb["next"].security_group_id == module.next.sg_id &&
      aws_vpc_security_group_ingress_rule.service_from_public_alb["auth"].security_group_id == module.auth.sg_id &&
      aws_vpc_security_group_ingress_rule.service_from_public_alb["clip"].security_group_id == module.clip.sg_id
    )
    error_message = "서비스 인그레스 ← 공개 ALB 가 next · auth · clip 셋이 아니다."
  }

  # ECR API · Secrets Manager · Logs(NAT 경유)와 ECR 레이어(S3 Gateway EP).
  assert {
    condition = alltrue([
      for service in ["next", "auth", "clip"] :
      local.egress_targets[service].aws_api.port == 443 &&
      local.egress_targets[service].aws_api.cidr_ipv4 == "0.0.0.0/0" &&
      local.egress_targets[service].s3.port == 443 &&
      local.egress_targets[service].s3.prefix_list_id == "pl-0example"
    ])
    error_message = "서비스 이그레스 443 → 0.0.0.0/0 · S3 prefix list 가 빠졌다."
  }
}

# --- 런타임 계약 검사 · 변수 검증 ---

run "runtime_contract_rejects_wildcard_db_user" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id              = "sg-0rds"
        rds_resource_id        = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn      = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users           = { auth = "app_*", clip = "app_clip" }
        app_env                = { auth = {}, clip = {} }
        app_secret_arns        = { auth = {}, clip = {} }
        app_task_resource_arns = { auth = {}, clip = {} }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

run "runtime_contract_rejects_wildcard_resource_arn" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id              = "sg-0rds"
        rds_resource_id        = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn      = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users           = { auth = "app_auth", clip = "app_clip" }
        app_env                = { auth = {}, clip = {} }
        app_secret_arns        = { auth = {}, clip = {} }
        app_task_resource_arns = { auth = {}, clip = { clips_bucket_arn = "arn:aws:s3:::*" } }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

run "runtime_contract_rejects_unknown_resource_key" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rds_sg_id              = "sg-0rds"
        rds_resource_id        = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        rewind_bucket_arn      = "arn:aws:s3:::pokeclip-prod-rewind-example"
        app_db_users           = { auth = "app_auth", clip = "app_clip" }
        app_env                = { auth = {}, clip = {} }
        app_secret_arns        = { auth = {}, clip = {} }
        app_task_resource_arns = { auth = {}, clip = { clip_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example" } }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

run "rejects_proxy_port_used_by_ingest_auth" {
  command = plan

  variables {
    proxy_ports = {
      live     = 8080
      playback = 28182
      control  = 28183
      health   = 28184
    }
  }

  expect_failures = [var.proxy_ports]
}

run "rejects_unknown_db_auth_mode" {
  command = plan

  variables {
    app_db_auth = "token"
  }

  expect_failures = [var.app_db_auth]
}

run "rejects_iam_without_ssl_root_cert" {
  command = plan

  variables {
    postgres_sslrootcert = null
  }

  expect_failures = [var.postgres_sslrootcert]
}

run "rejects_stream_key_prefix_with_wildcard" {
  command = plan

  variables {
    stream_key_secret_prefix = "pokeclip/prod/*/"
  }

  expect_failures = [var.stream_key_secret_prefix]
}

run "rejects_image_tag_instead_of_digest" {
  command = plan

  variables {
    image_digests = {
      next = "latest"
      auth = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
      clip = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
    }
  }

  expect_failures = [var.image_digests]
}
