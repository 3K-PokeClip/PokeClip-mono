# 출력 app_env(POK-309 · 설계 3절) — 서비스별 비밀 아닌 env 의 키 집합과 값이
# RDS · 계약9 큐 · stream-key 접두를 가리키는지 단언한다(CONTRACT-runtime.md
# 「런타임 1단 → platform」 행). 주소 · URL 은 mock 이 만드는 가짜 값이다.
# override_data 가 network 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

mock_provider "aws" {
  source = "./tests/mocks"
}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id                  = "vpc-0example"
      private_subnet_ids      = ["subnet-0examplea", "subnet-0examplec"]
      media_az                = "ap-northeast-2c"
      media_private_subnet_id = "subnet-0examplec"
    }
  }
}


# env 이름은 각 서비스 application.yml 실물이다. 이름을 더하면 이 집합도
# 함께 고친다(POSTGRES_USER 는 platform 이 app_db_users 로 넣는다).
run "lists_env_names_per_service" {
  command = apply

  assert {
    condition = toset(keys(output.app_env)) == toset([
      "auth", "clip", "chat-collector", "chat-detector",
    ])
    error_message = "app_env 의 서비스 집합이 auth · clip · chat-collector · chat-detector 와 다르다."
  }

  assert {
    condition = toset(keys(output.app_env["auth"])) == toset([
      "DB_HOST", "DB_PORT", "POSTGRES_DB", "STREAM_KEY_SECRET_REF_PREFIX",
    ])
    error_message = "auth 의 env 이름 집합이 DB_HOST · DB_PORT · POSTGRES_DB · STREAM_KEY_SECRET_REF_PREFIX 와 다르다."
  }

  assert {
    condition = toset(keys(output.app_env["clip"])) == toset([
      "DB_HOST", "DB_PORT", "POSTGRES_DB", "BROADCAST_QUEUE_URL",
      "BROADCAST_INTAKE_ENABLED",
    ])
    error_message = "clip 의 env 이름 집합이 DB_HOST · DB_PORT · POSTGRES_DB · BROADCAST_QUEUE_URL · BROADCAST_INTAKE_ENABLED 와 다르다."
  }

  assert {
    condition = toset(keys(output.app_env["chat-collector"])) == toset([
      "DB_HOST", "DB_PORT", "POSTGRES_DB", "BROADCAST_QUEUE_URL",
    ])
    error_message = "chat-collector 의 env 이름 집합이 DB_HOST · DB_PORT · POSTGRES_DB · BROADCAST_QUEUE_URL 과 다르다."
  }

  assert {
    condition = toset(keys(output.app_env["chat-detector"])) == toset([
      "DB_HOST", "DB_PORT", "POSTGRES_DB",
    ])
    error_message = "chat-detector 의 env 이름 집합이 DB_HOST · DB_PORT · POSTGRES_DB 와 다르다."
  }
}

# 주소는 계산 속성이라 apply 로 돌려 mock 이 값을 만들게 한다. endpoint 는
# 호스트:포트라 DB_HOST 가 될 수 없다 — 둘이 다른 값인지 먼저 본다.
run "points_db_env_at_rds_instance" {
  command = apply

  assert {
    condition     = aws_db_instance.this.address != aws_db_instance.this.endpoint
    error_message = "mock 이 address 와 endpoint 에 같은 값을 줬다 — DB_HOST 단언이 의미를 잃는다."
  }

  assert {
    condition = alltrue([
      for env in values(output.app_env) :
      env["DB_HOST"] == aws_db_instance.this.address
    ])
    error_message = "DB_HOST 가 RDS address(호스트만)가 아니다."
  }

  # 태스크 env 값은 문자열이다 — 숫자 5432 가 아니라 문자열 "5432".
  assert {
    condition = alltrue([
      for env in values(output.app_env) : env["DB_PORT"] == "5432"
    ])
    error_message = "DB_PORT 가 문자열 \"5432\" 가 아니다."
  }

  assert {
    condition = alltrue([
      for env in values(output.app_env) :
      env["POSTGRES_DB"] == aws_db_instance.this.db_name && env["POSTGRES_DB"] == "pokeclip"
    ])
    error_message = "POSTGRES_DB 가 RDS DB 이름 pokeclip 이 아니다."
  }
}

# 방송 편지는 팬아웃이라 두 서버가 각자 자기 큐를 받는다 — 같은 큐를 가리키면
# 두 서버가 편지를 서로 뺏는다(계약9 1절).
run "gives_each_consumer_its_own_queue_url" {
  command = apply

  assert {
    condition     = output.app_env["clip"]["BROADCAST_QUEUE_URL"] == aws_sqs_queue.broadcast["clip"].url
    error_message = "clip 의 BROADCAST_QUEUE_URL 이 clip 큐 URL 이 아니다."
  }

  assert {
    condition     = output.app_env["chat-collector"]["BROADCAST_QUEUE_URL"] == aws_sqs_queue.broadcast["chat"].url
    error_message = "chat-collector 의 BROADCAST_QUEUE_URL 이 chat 큐 URL 이 아니다."
  }

  assert {
    condition     = output.app_env["clip"]["BROADCAST_QUEUE_URL"] != output.app_env["chat-collector"]["BROADCAST_QUEUE_URL"]
    error_message = "clip 과 chat-collector 가 같은 큐 URL 을 받는다."
  }
}

# auth 가 스트림 키 비밀을 만들고 찾는 이름 접두(dev 는 pokeclip/dev/stream-key/).
run "prefixes_stream_key_secrets_under_prod" {
  command = apply

  assert {
    condition     = output.app_env["auth"]["STREAM_KEY_SECRET_REF_PREFIX"] == "pokeclip/prod/stream-key/"
    error_message = "auth 의 STREAM_KEY_SECRET_REF_PREFIX 가 pokeclip/prod/stream-key/ 가 아니다."
  }
}

# 큐 수신 스위치(서버 기본 false)는 clip 만 런타임이 켠다(kty 2026-10-24).
# 빠지거나 false 면 clip 은 뜨지만 큐를 읽지 않아 편지가 보관 기간 뒤
# 사라진다. 태스크 env 값은 문자열이라 불리언 true 가 아니라 "true" 다.
# chat-collector 는 배치(POK-306)가 AUTH_BASE_URL 과 함께 켠다.
run "turns_on_broadcast_intake_for_clip_only" {
  command = apply

  assert {
    condition     = lookup(output.app_env["clip"], "BROADCAST_INTAKE_ENABLED", null) == "true"
    error_message = "clip 의 BROADCAST_INTAKE_ENABLED 가 문자열 \"true\" 가 아니다."
  }

  assert {
    condition     = !contains(keys(output.app_env["chat-collector"]), "BROADCAST_INTAKE_ENABLED")
    error_message = "chat-collector 에 BROADCAST_INTAKE_ENABLED 가 있다 — 배치(POK-306)가 정한다."
  }
}
