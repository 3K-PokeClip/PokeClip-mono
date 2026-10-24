# prod 앱 비밀 그릇 아홉(pokeclip/prod/<용도>)과 출력 셋(CONTRACT-runtime.md
# 「런타임 1단 → platform · media」 행)을 단언한다. network 출력은 가짜 값이다.
# override_data 가 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

mock_provider "aws" {
  source = "./tests/mocks"
}

# auth_oauth_apps 는 기본값이 없다(auth_settings.tf — tfvars 필수). 이 파일의
# 모든 run 에 흐르는 예시 값이다(run 안의 variables 가 덮는 변수만 바뀐다).
variables {
  auth_oauth_apps = {
    google  = { client_id = "example-google-client-id", redirect_uri = "https://app.pokeclip.com/auth/callback" }
    chzzk   = { client_id = "example-chzzk-client-id", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
    youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
    soop    = { client_id = "example-soop-client-id", redirect_uri = "https://app.pokeclip.com/oauth/soop/callback" }
  }
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

run "creates_eight_app_secret_shells" {
  command = plan

  assert {
    condition = toset(keys(module.secret_shells.secret_arns)) == toset([
      "pokeclip/prod/jwt-secret",
      "pokeclip/prod/secret-store-key",
      "pokeclip/prod/internal-api-token",
      "pokeclip/prod/google-client-secret",
      "pokeclip/prod/youtube-client-secret",
      "pokeclip/prod/chzzk-client-secret",
      "pokeclip/prod/profile-photo-token-secret",
      "pokeclip/prod/soop-client-secret",
      "pokeclip/prod/stream-key-resolve-token",
    ])
    error_message = "비밀 그릇 이름 집합이 pokeclip/prod/ 아래 여덟과 다르다."
  }
}

# ARN 은 계산 속성이다. apply 로 돌려 mock 이 그릇마다 다른 ARN 을 만들게 한
# 뒤, 출력의 env 이름마다 맞는 그릇을 가리키는지 본다.
run "maps_services_to_secret_env_names" {
  command = apply

  assert {
    condition = toset(keys(output.app_secret_arns)) == toset([
      "auth", "clip", "chat-collector", "chat-detector", "render", "upload",
    ])
    error_message = "app_secret_arns 의 서비스 집합이 auth · clip · chat-collector · chat-detector · render · upload 와 다르다."
  }

  assert {
    condition = toset(keys(output.app_secret_arns["auth"])) == toset([
      "JWT_SECRET",
      "SECRET_STORE_KEY",
      "INTERNAL_API_TOKEN",
      "GOOGLE_CLIENT_SECRET",
      "YOUTUBE_CLIENT_SECRET",
      "CHZZK_CLIENT_SECRET",
      "PROFILE_PHOTO_TOKEN_SECRET",
      "SOOP_CLIENT_SECRET",
      "STREAM_KEY_RESOLVE_TOKEN",
    ])
    error_message = "auth 의 env 이름 집합이 아홉(JWT_SECRET 부터 STREAM_KEY_RESOLVE_TOKEN)과 다르다."
  }

  assert {
    condition = toset(keys(output.app_secret_arns["clip"])) == toset([
      "JWT_SECRET", "INTERNAL_API_TOKEN",
    ])
    error_message = "clip 의 env 이름 집합이 JWT_SECRET · INTERNAL_API_TOKEN 과 다르다."
  }

  assert {
    condition = toset(keys(output.app_secret_arns["chat-collector"])) == toset([
      "INTERNAL_API_TOKEN", "CHZZK_CLIENT_SECRET",
    ])
    error_message = "chat-collector 의 env 이름 집합이 INTERNAL_API_TOKEN · CHZZK_CLIENT_SECRET 과 다르다."
  }

  assert {
    condition = toset(keys(output.app_secret_arns["chat-detector"])) == toset([
      "INTERNAL_API_TOKEN",
    ])
    error_message = "chat-detector 의 env 이름 집합이 INTERNAL_API_TOKEN 하나가 아니다."
  }

  # 일꾼 둘은 서버 넷과 같은 INTERNAL_API_TOKEN 하나만 읽는다(D6 — 비면 부팅
  # 거부). platform 은 auth · clip 만 읽고, 일꾼 키는 배치 루트가 읽는다(K1).
  assert {
    condition = alltrue([
      for worker in ["render", "upload"] :
      toset(keys(output.app_secret_arns[worker])) == toset(["INTERNAL_API_TOKEN"])
    ])
    error_message = "render · upload 의 env 이름 집합이 INTERNAL_API_TOKEN 하나가 아니다."
  }

  assert {
    condition     = length(output.media_secret_arns) == 1
    error_message = "media_secret_arns 의 원소가 하나가 아니다."
  }
}

# 이 루트가 만들지 않는 비밀 둘: CLOUDFRONT_PRIVATE_KEY_PEM 은 prod/secrets 의
# 서명 키 그릇을 platform 이 합친다(K2). POSTGRES_PASSWORD 는 대체 갈래에서만
# 생긴다(설계 9절 4). resolve 전용 토큰은 auth 만 받는다 — 서비스 집합을 손으로
# 적지 않고 출력의 키에서 auth 를 뺀 나머지를 돈다.
run "omits_secrets_owned_elsewhere" {
  command = apply

  assert {
    condition = alltrue([
      for env_arns in values(output.app_secret_arns) :
      length(setintersection(toset(keys(env_arns)), toset([
        "CLOUDFRONT_PRIVATE_KEY_PEM",
        "POSTGRES_PASSWORD",
      ]))) == 0
    ])
    error_message = "출력에 CLOUDFRONT_PRIVATE_KEY_PEM · POSTGRES_PASSWORD 가운데 하나가 있다."
  }

  assert {
    condition = alltrue([
      for service in setsubtract(keys(output.app_secret_arns), ["auth"]) :
      !contains(keys(output.app_secret_arns[service]), "STREAM_KEY_RESOLVE_TOKEN")
    ])
    error_message = "auth 밖 서비스에 STREAM_KEY_RESOLVE_TOKEN 이 있다 — resolve 전용 토큰은 auth 만 받는다."
  }
}

run "points_each_env_name_at_its_shell" {
  command = apply

  assert {
    condition     = length(distinct(values(module.secret_shells.secret_arns))) == 9
    error_message = "mock 이 그릇 아홉에 서로 다른 ARN 을 주지 않았다 — 아래 단언이 의미를 잃는다."
  }

  assert {
    condition = output.app_secret_arns["auth"] == {
      JWT_SECRET                 = module.secret_shells.secret_arns["pokeclip/prod/jwt-secret"]
      SECRET_STORE_KEY           = module.secret_shells.secret_arns["pokeclip/prod/secret-store-key"]
      INTERNAL_API_TOKEN         = module.secret_shells.secret_arns["pokeclip/prod/internal-api-token"]
      GOOGLE_CLIENT_SECRET       = module.secret_shells.secret_arns["pokeclip/prod/google-client-secret"]
      YOUTUBE_CLIENT_SECRET      = module.secret_shells.secret_arns["pokeclip/prod/youtube-client-secret"]
      CHZZK_CLIENT_SECRET        = module.secret_shells.secret_arns["pokeclip/prod/chzzk-client-secret"]
      PROFILE_PHOTO_TOKEN_SECRET = module.secret_shells.secret_arns["pokeclip/prod/profile-photo-token-secret"]
      SOOP_CLIENT_SECRET         = module.secret_shells.secret_arns["pokeclip/prod/soop-client-secret"]
      STREAM_KEY_RESOLVE_TOKEN   = module.secret_shells.secret_arns["pokeclip/prod/stream-key-resolve-token"]
    }
    error_message = "auth 의 env 이름이 같은 용도의 그릇 ARN 을 가리키지 않는다."
  }

  # 같은 env 이름은 서비스가 달라도 같은 그릇(같은 ARN)이다. auth 가 아홉을
  # 모두 가지므로 auth 를 기준으로 본다 — 일꾼의 INTERNAL_API_TOKEN 도 auth 의
  # 것과 같은 ARN 이다(1단-가 K1).
  assert {
    condition = alltrue(flatten([
      for service, env_arns in output.app_secret_arns : [
        for env_name, arn in env_arns :
        arn == output.app_secret_arns["auth"][env_name]
      ]
    ]))
    error_message = "같은 env 이름이 서비스에 따라 다른 ARN 을 가리킨다."
  }
}

# Media 상자가 읽는 auth 출입증은 resolve 전용 토큰 그릇 하나다(POK-429 · K2).
# 공용 INTERNAL_API_TOKEN 은 auth 의 /internal/** 전부를 열므로 목록에 없어야
# 한다. 소비자 media_host 의 변수가 list(string) 이라 목록과 같은지로 꼴도
# 함께 본다.
run "gives_media_only_the_resolve_token_shell" {
  command = apply

  assert {
    condition     = output.app_secret_arns["auth"]["STREAM_KEY_RESOLVE_TOKEN"] != output.app_secret_arns["auth"]["INTERNAL_API_TOKEN"]
    error_message = "resolve 전용 토큰과 공용 INTERNAL_API_TOKEN 이 같은 그릇 ARN 이다 — 아래 단언이 의미를 잃는다."
  }

  assert {
    condition     = output.media_secret_arns == [output.app_secret_arns["auth"]["STREAM_KEY_RESOLVE_TOKEN"]]
    error_message = "media_secret_arns 가 auth 의 STREAM_KEY_RESOLVE_TOKEN ARN 하나만 든 목록이 아니다."
  }

  assert {
    condition     = !contains(output.media_secret_arns, output.app_secret_arns["auth"]["INTERNAL_API_TOKEN"])
    error_message = "media_secret_arns 에 공용 INTERNAL_API_TOKEN 그릇 ARN 이 있다."
  }
}

# 응답기 media.env 렌더(M15)는 목록의 순서([0])에 기대지 않고 이름 있는 출력을
# 읽는다(K5). 그 출력과 읽기 정책 목록이 같은 그릇을 가리키는지 묶는다.
run "names_resolve_token_arn_for_ingest_auth" {
  command = apply

  assert {
    condition     = output.ingest_auth_resolve_token_secret_arn == output.app_secret_arns["auth"]["STREAM_KEY_RESOLVE_TOKEN"]
    error_message = "ingest_auth_resolve_token_secret_arn 이 auth 의 STREAM_KEY_RESOLVE_TOKEN ARN 이 아니다."
  }

  assert {
    condition     = contains(output.media_secret_arns, output.ingest_auth_resolve_token_secret_arn)
    error_message = "ingest_auth_resolve_token_secret_arn 이 media_secret_arns 에 없다 — 응답기가 읽을 권한이 없는 그릇을 받는다."
  }
}

# 소비자 modules/media_host 의 변수 media_secret_arns 검증과 같은 정규식이다.
# 바꾸면 두 곳을 함께 고친다. mock ARN 은 임의 문자열이라 이 run 에서만 그릇에
# ARN 꼴 값을 준다(계정 자리는 한 자리 — 계정 ID 숫자열 없음). 앞 run 이 만든
# 그릇은 상태에 이미 있어 override 가 닿지 않으므로 상태를 따로 쓴다.
run "media_secret_arns_pass_media_host_validation" {
  command   = apply
  state_key = "media_arn_shape"

  override_resource {
    target = module.secret_shells.aws_secretsmanager_secret.this
    values = {
      arn = "arn:aws:secretsmanager:ap-northeast-2:0:secret:pokeclip/prod/stream-key-resolve-token-AbCdEf"
    }
  }

  assert {
    condition = alltrue([
      for arn in output.media_secret_arns :
      can(regex("^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]+:secret:[A-Za-z0-9/_+=.@-]+$", arn))
    ])
    error_message = "media_secret_arns 의 원소가 media_host 의 Secrets Manager ARN 검증을 통과하지 못한다."
  }

  assert {
    condition     = can(regex("^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]+:secret:[A-Za-z0-9/_+=.@-]+$", output.ingest_auth_resolve_token_secret_arn))
    error_message = "ingest_auth_resolve_token_secret_arn 이 media_host 의 Secrets Manager ARN 검증과 같은 꼴이 아니다."
  }
}
