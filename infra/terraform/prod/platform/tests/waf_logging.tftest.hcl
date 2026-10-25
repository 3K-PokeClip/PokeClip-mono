# prod/platform 의 공개 ALB REGIONAL WAF 트래픽 로그(장부 17 · kty 결정)와 웹
# ACL 데이터 보호를 mock provider 로 단언한다 — CloudWatch Logs 로그 그룹
# aws-waf-logs-pokeclip-prod-public · 보존은 ECS 로그와 같은 30일 ·
# authorization · cookie 헤더 가림, 그리고 그 두 헤더 · 내부 토큰 헤더 둘 ·
# 질의 인자 code · token 을 요청 샘플 · 로그 모두에서 가리는 데이터 보호.
# 차단(BLOCK)만 남기는 필터 · SUBSTITUTION 치환은
# waf_acl 모듈 시험이 맡는다(루트 시험은 모듈 안 자원을 주소로 볼 수 없다).
#
# 앞 루트 상태 흉내 · 가짜 값은 apply_order.tftest.hcl 과 같다(시험 파일마다
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

# --- 공개 WAF 트래픽 로그 ---

run "logs_public_waf_blocks_to_cloudwatch" {
  command = plan

  assert {
    condition = (
      module.waf.logging.log_group_name == "aws-waf-logs-pokeclip-prod-public" &&
      module.waf.logging.retention_in_days == local.log_retention_days &&
      local.log_retention_days == 30
    )
    error_message = "공개 WAF 로그 그룹이 aws-waf-logs-pokeclip-prod-public · ECS 로그와 같은 보존(30일)이 아니다."
  }

  assert {
    condition     = toset(module.waf.logging.redacted_headers) == toset(["authorization", "cookie"])
    error_message = "공개 WAF 로그가 authorization · cookie 헤더만 가리지 않는다."
  }
}

# --- 공개 WAF 데이터 보호 ---

# 요청 샘플에 Authorization · 쿠키 · 내부 토큰 헤더가 원문으로 남지 않게 웹
# ACL 데이터 보호로 그 헤더들을 가린다(POK-459 · POK-479).
run "protects_public_waf_credentials_in_samples" {
  command = plan

  assert {
    condition = toset(module.waf.protected_headers) == toset([
      "authorization",
      "cookie",
      "x-internal-token",
      "x-stream-key-resolve-token",
    ])
    error_message = "공개 WAF 데이터 보호가 authorization · cookie · x-internal-token · x-stream-key-resolve-token 헤더만 가리지 않는다."
  }
}

# OAuth 콜백의 ?code= 와 프로필 사진 주소의 ?token= 은 공개 ALB(app 호스트)로
# 온다 — 그 질의 인자 값도 요청 샘플 · 로그 모두에서 가린다(POK-479).
run "protects_public_waf_credential_query_args" {
  command = plan

  assert {
    condition     = toset(module.waf.protected_query_args) == toset(["code", "token"])
    error_message = "공개 WAF 데이터 보호가 질의 인자 code · token 만 가리지 않는다."
  }
}
