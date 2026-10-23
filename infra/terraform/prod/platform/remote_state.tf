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

    # 런타임 app_env 는 평문 태스크 env 로 들어가 태스크 정의를 읽는 누구에게나
    # 보인다 — 비밀은 app_secret_arns 로 와야 한다(장부 16). 아래 셋으로 본다.
    #
    # 이름 꼴: Spring 은 jwt.secret · JWT-SECRET 같은 env 도 같은 속성으로 묶어
    # (relaxed binding) _ 토막 검사를 비켜 간다. 앱이 읽는 이름은 대문자 env
    # 이므로 그 꼴만 받는다.
    precondition {
      condition = alltrue(flatten([
        for env in values(try(local.runtime.app_env, {})) : [
          for name in keys(env) : can(regex("^[A-Z][A-Z0-9_]*$", name))
        ]
      ]))
      error_message = "런타임 출력 app_env 에 대문자 · 숫자 · _ 꼴(^[A-Z][A-Z0-9_]*$)이 아닌 이름이 있다 — 점 · 하이픈 · 소문자 이름은 받지 않는다."
    }

    # 비밀 낱말: 이름 어디에 들든(JWT_SECRET_B64 · DB_PASSWORD_PROD · 구분자
    # 없는 APIKEY · DBPASSWORD) 멈춘다. 낱말에 _ 가 없어 이름 전체에서 찾는
    # 것이 토막마다 찾는 것과 같다. 예외 둘 — 계약(CONTRACT-runtime.md)의
    # 비밀 아닌 이름과 app_env_non_secret_names. 계약 이름은 변수 기본값이
    # 아니라 여기 둔다 — 변수를 덮을 때 함께 사라지면 계약대로 준 런타임이
    # 멈춘다. STREAM_KEY_RESOLVE_ALLOWED_CIDRS 는 런타임이 app_env 로 주는
    # 이름, 나머지 넷은 platform 이 정해 런타임 값을 덮는 이름이다.
    precondition {
      condition = alltrue(flatten([
        for env in values(try(local.runtime.app_env, {})) : [
          for name in keys(env) :
          contains(concat(var.app_env_non_secret_names, [
            "STREAM_KEY_RESOLVE_ALLOWED_CIDRS",
            "STREAM_KEY_SECRET_STORE_TYPE",
            "STREAM_KEY_SECRET_RETIRE_FROM",
            "STREAM_KEY_SECRET_REF_PREFIX",
            "CLOUDFRONT_KEY_PAIR_ID",
          ]), name) ||
          !anytrue([
            for word in ["PASSWORD", "PASSWD", "PASSPHRASE", "SECRET", "TOKEN", "KEY", "PEM", "CREDENTIAL"] :
            strcontains(upper(name), word)
          ])
        ]
      ]))
      error_message = "런타임 출력 app_env 이름에 PASSWORD · PASSWD · PASSPHRASE · SECRET · TOKEN · KEY · PEM · CREDENTIAL 이 들어 있다 — 비밀이면 런타임 app_secret_arns 로 옮기고, 비밀이 아니면 app_env_non_secret_names 에 적는다."
    }

    # 값: URL 의 user:pass@ 는 이름과 무관하게 비밀이다. app_env_non_secret_names
    # 는 이름 검사만 풀고 이 검사는 풀지 않는다. 판정은 :// 마다 그 뒤 조각에
    # 따로 한다 — 쿼리 안에 넣은 URL 의 자격 증명도 잡으려고. / 앞의 :…@ 를
    # 넓게 잡고 ? · # 에서 끊지 않는다 — libpq 처럼 인코딩 없는 ? · # 를
    # 비밀번호에 받는 클라이언트가 있다. 「호스트(+포트) 뒤 ? · # 면 쿼리」
    # 로 빼 주지 않는다 — https://h:443?q@x 와 postgres://u:5432?비밀@h 는
    # 글자로 구별되지 않아 면제가 곧 우회다. 알려진 한계(오탐): 쿼리 ·
    # 프래그먼트 속 user:x@ 꼴(https://h:443?owner=ops@x)도 멈춘다 — 그런
    # 값은 app_secret_arns 로 옮긴다.
    precondition {
      condition = alltrue(flatten([
        for env in values(try(local.runtime.app_env, {})) : [
          for value in values(env) : !anytrue([
            for i, seg in split("://", value) :
            i > 0 && can(regex("^[^/@]*:[^/@]*@", seg))
          ])
        ]
      ]))
      error_message = "런타임 출력 app_env 값에 URL 자격 증명(scheme://user:pass@)이 있다 — 자격 증명은 런타임 app_secret_arns 로 옮긴다."
    }

    # 값 운반 이름: 값 하나에 여러 속성을 싣는 env 는 이름 검사를 통째로
    # 비켜 간다 — SPRING_APPLICATION_JSON(Spring Boot 가 JSON 을 속성으로
    # 편다) · JAVA_TOOL_OPTIONS(JVM) · JDK_JAVA_OPTIONS(java 실행기, JDK 9+) ·
    # JAVA_OPTS(셸 시작 스크립트 관례 — 지금 이미지의 exec 꼴 java -jar 는
    # 읽지 않지만 시작 스크립트로 바뀌면 실린다). 같은 부류 _JAVA_OPTIONS
    # (HotSpot) 와 소문자 변형은 위 이름 꼴 검사가 이미 막는다.
    # app_env_non_secret_names 로 풀지 않는다.
    precondition {
      condition = alltrue(flatten([
        for env in values(try(local.runtime.app_env, {})) : [
          for name in keys(env) : !contains([
            "SPRING_APPLICATION_JSON",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "JAVA_OPTS",
          ], name)
        ]
      ]))
      error_message = "런타임 출력 app_env 에 값 운반 이름(SPRING_APPLICATION_JSON · JAVA_TOOL_OPTIONS · JDK_JAVA_OPTIONS · JAVA_OPTS)이 있다 — 속성은 앱이 읽는 이름 하나씩 넣고, 비밀은 런타임 app_secret_arns 로 옮긴다."
    }

    # 값 운반 꼴: 이름이 무엇이든 값에 비밀을 싣는 꼴 셋을 멈춘다.
    # (1) URL 쿼리 · 프래그먼트의 비밀 매개변수 — ? · & · ; · # 뒤 키
    # 이름에 passw · pwd · secret · token 이 들면(passwd · sslpassword ·
    # client_secret · OAuth implicit 의 #access_token 등, 대소문자 무관).
    # 키=값 꼴이 아닌 프래그먼트(#section)는 걸지 않는다. 알려진 한계(오탐): token_type ·
    # page_token 처럼 비밀 아닌 키도 멈춘다. (2) 비밀 낱말이 든
    # JVM 시스템 속성 -D…(속성 이름은 대소문자 무관). JVM 은 옵션을 공백으로
    # 나누므로 -D 는 값 처음 · 공백 · 따옴표 뒤만 본다 — pokeclip-Data-token
    # 같은 하이픈 이름은 걸지 않는다. -d 는 시스템 속성이 아니다. (3) 콜론
    # 없는 userinfo(https://<토큰>@host). 알려진 한계(오탐): ssh://git@host
    # 처럼 비밀 아닌 사용자 이름만 둔 URL 과 쿼리 속 @(https://h?owner=ops@x)
    # 도 멈춘다 — 계약 이름의 값 꼴(주소 · 큐 URL · 버킷 · 대역 · 기간)에는
    # 없다.
    precondition {
      condition = alltrue(flatten([
        for env in values(try(local.runtime.app_env, {})) : [
          for value in values(env) : !(
            can(regex("(?i)[?&;#][^=&;#?]*(passw|pwd|secret|token)[^=&;#?]*=", value)) ||
            can(regex("(^|[\\s\"'])-D[^ =]*(?i:secret|password|token)", value)) ||
            anytrue([
              for i, seg in split("://", value) :
              i > 0 && can(regex("^[^/@:]+@", seg))
            ])
          )
        ]
      ]))
      error_message = "런타임 출력 app_env 값에 비밀을 싣는 꼴(키에 passw · pwd · secret · token 이 든 쿼리 · 프래그먼트 매개변수 · 비밀 낱말이 든 -D 속성 · https://<토큰>@host)이 있다 — 비밀은 런타임 app_secret_arns 로 옮긴다."
    }
  }
}
