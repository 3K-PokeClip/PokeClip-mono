# 앞 루트 출력(설계 3-2 · 3-6). 같은 환경 안의 값이라 terraform_remote_state
# 로 읽는다. 런타임 1단 출력의 꼴은 CONTRACT-runtime.md 가 정본이다.

data "terraform_remote_state" "network" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.network
    region = var.region
  }
}

data "terraform_remote_state" "certs" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.certs
    region = var.region
  }
}

data "terraform_remote_state" "secrets" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.secrets
    region = var.region
  }
}

data "terraform_remote_state" "registry" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.registry
    region = var.region
  }
}

data "terraform_remote_state" "runtime" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.runtime
    region = var.region
  }
}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  network = data.terraform_remote_state.network.outputs
  certs   = data.terraform_remote_state.certs.outputs
  secrets = data.terraform_remote_state.secrets.outputs
  runtime = data.terraform_remote_state.runtime.outputs

  # 작업 역할 정책 대상(런타임 출력 app_task_resource_arns) — 서비스마다 받는
  # 키. 꺼진 기능의 키는 런타임이 내지 않고, 그 문장은 만들지 않는다.
  task_resource_keys = {
    auth = ["profile_photo_bucket_arn"]
    clip = [
      "broadcast_queue_arn",
      "render_queue_arn",
      "render_dlq_arn",
      "upload_queue_arn",
      "upload_dlq_arn",
      "thumbnail_queue_arn",
      "clips_bucket_arn",
      "segment_bucket_arn",
    ]
  }

  task_resource_arns = {
    for service in keys(local.task_resource_keys) :
    service => try(local.runtime.app_task_resource_arns[service], {})
  }
}

# 런타임 1단 출력을 정책 · SG 규칙에 쓰기 전에 꼴을 본다. 와일드카드가 든 값이
# 정책 Resource 에 그대로 들어가지 않게 한다(공통 지시 5 · 6).
resource "terraform_data" "runtime_contract" {
  lifecycle {
    precondition {
      condition     = can(regex("^sg-[A-Za-z0-9]+$", local.runtime.rds_sg_id))
      error_message = "런타임 출력 rds_sg_id 가 SG id(sg-…)가 아니다."
    }

    precondition {
      condition = alltrue([
        for service in ["auth", "clip"] :
        can(regex("^[a-z_][a-z0-9_]{0,62}$", local.runtime.app_db_users[service]))
      ])
      error_message = "런타임 출력 app_db_users 의 auth · clip 이 없거나 DB 사용자 이름(소문자 · 숫자 · _, 와일드카드 없음)이 아니다."
    }

    precondition {
      condition     = var.app_db_auth != "iam" || can(regex("^(db|cluster)-[A-Za-z0-9]+$", local.runtime.rds_resource_id))
      error_message = "app_db_auth = iam 인데 런타임 출력 rds_resource_id 가 db-<영숫자> · cluster-<영숫자> 꼴이 아니다."
    }

    precondition {
      condition = alltrue([
        for service, arns in local.task_resource_arns :
        length(setsubtract(keys(arns), local.task_resource_keys[service])) == 0
      ])
      error_message = "런타임 출력 app_task_resource_arns 에 모르는 키가 있다(계약 키 이름 확인)."
    }

    # SQS 큐 이름은 영숫자 · - · _ 이고 FIFO 큐만 .fifo 로 끝난다(SQS
    # CreateQueue API 참조 QueueName). 점은 그 접미에만 허용한다.
    precondition {
      condition = alltrue(flatten([
        for arns in values(local.task_resource_arns) : [
          for key, arn in arns :
          endswith(key, "_bucket_arn") ? can(regex("^arn:aws:s3:::[a-z0-9][a-z0-9.-]+$", arn)) : can(regex("^arn:aws:sqs:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+(\\.fifo)?$", arn))
        ]
      ]))
      error_message = "런타임 출력 app_task_resource_arns 의 값이 와일드카드 없는 S3 버킷 · SQS 큐 ARN 이 아니다."
    }

    # iam 에서 POSTGRES_PASSWORD 가 비어 있지 않으면 auth · clip 이 부팅을
    # 거부한다(db-support DataSourceLoginConfiguration.rejectLeftoverPassword).
    # env 든 secrets 든 같은 이름이면 앱이 읽는다.
    precondition {
      condition = var.app_db_auth != "iam" || alltrue([
        for service in ["auth", "clip"] :
        !contains(concat(keys(local.service_env[service]), keys(local.service_secrets[service])), "POSTGRES_PASSWORD")
      ])
      error_message = "app_db_auth = iam 인데 런타임 출력이 auth · clip 에 POSTGRES_PASSWORD 를 준다 — 앱이 부팅을 거부한다. 런타임 app_secret_arns · app_env 에서 지운다."
    }

    # auth 는 resolve 전용 토큰이 있으면 허용 대역이 빌 때 부팅을 거부한다
    # (auth StreamKeyResolveSecurityConfig.requireSafeToOpen). 앱은 env 든
    # secrets 든 같은 이름이면 읽는다. secrets 값은 plan 에서 볼 수 없어 키가
    # 있으면 값이 있다고 보고, env 값은 공백뿐이면 빈 값으로 본다(앱의
    # isBlank 와 같다).
    precondition {
      condition = (
        !(
          contains(keys(local.service_secrets.auth), "STREAM_KEY_RESOLVE_TOKEN") ||
          trimspace(lookup(local.service_env.auth, "STREAM_KEY_RESOLVE_TOKEN", "")) != ""
        ) ||
        contains(keys(local.service_secrets.auth), "STREAM_KEY_RESOLVE_ALLOWED_CIDRS") ||
        trimspace(lookup(local.service_env.auth, "STREAM_KEY_RESOLVE_ALLOWED_CIDRS", "")) != ""
      )
      error_message = "런타임 출력이 auth 에 STREAM_KEY_RESOLVE_TOKEN 을 주는데(app_secret_arns · app_env 어느 쪽이든) STREAM_KEY_RESOLVE_ALLOWED_CIDRS 가 둘 다에 없거나 app_env 값이 비었다 — auth 가 부팅을 거부한다."
    }
  }
}
