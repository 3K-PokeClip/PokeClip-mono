# prod 앱 비밀 그릇 일곱(pokeclip/prod/<용도>)과 출력 둘(CONTRACT-runtime.md
# 「런타임 1단 → platform · media」 행)을 단언한다. network 출력은 가짜 값이다.
# override_data 가 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

mock_provider "aws" {}

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

run "creates_seven_app_secret_shells" {
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
    ])
    error_message = "비밀 그릇 이름 집합이 pokeclip/prod/ 아래 일곱과 다르다."
  }
}

# ARN 은 계산 속성이다. apply 로 돌려 mock 이 그릇마다 다른 ARN 을 만들게 한
# 뒤, 출력의 env 이름마다 맞는 그릇을 가리키는지 본다.
run "maps_services_to_secret_env_names" {
  command = apply

  assert {
    condition = toset(keys(output.app_secret_arns)) == toset([
      "auth", "clip", "chat-collector", "chat-detector",
    ])
    error_message = "app_secret_arns 의 서비스 집합이 auth · clip · chat-collector · chat-detector 와 다르다."
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
    ])
    error_message = "auth 의 env 이름 집합이 일곱(JWT_SECRET 부터 PROFILE_PHOTO_TOKEN_SECRET)과 다르다."
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

  assert {
    condition = toset(keys(output.media_secret_arns)) == toset([
      "INTERNAL_API_TOKEN",
    ])
    error_message = "media_secret_arns 의 env 이름 집합이 INTERNAL_API_TOKEN 하나가 아니다."
  }
}

# 이 루트가 만들지 않는 비밀 셋: CLOUDFRONT_PRIVATE_KEY_PEM 은 prod/secrets 의
# 서명 키 그릇을 platform 이 합친다(K2). STREAM_KEY_RESOLVE_TOKEN 은 prod 에서
# 비운다(POK-305 10546). POSTGRES_PASSWORD 는 대체 갈래에서만 생긴다(설계 9절 4).
run "omits_secrets_owned_elsewhere" {
  command = apply

  assert {
    condition = alltrue([
      for env_arns in concat(values(output.app_secret_arns), [output.media_secret_arns]) :
      length(setintersection(toset(keys(env_arns)), toset([
        "CLOUDFRONT_PRIVATE_KEY_PEM",
        "STREAM_KEY_RESOLVE_TOKEN",
        "POSTGRES_PASSWORD",
      ]))) == 0
    ])
    error_message = "출력에 CLOUDFRONT_PRIVATE_KEY_PEM · STREAM_KEY_RESOLVE_TOKEN · POSTGRES_PASSWORD 가운데 하나가 있다."
  }
}

run "points_each_env_name_at_its_shell" {
  command = apply

  assert {
    condition     = length(distinct(values(module.secret_shells.secret_arns))) == 7
    error_message = "mock 이 그릇 일곱에 서로 다른 ARN 을 주지 않았다 — 아래 단언이 의미를 잃는다."
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
    }
    error_message = "auth 의 env 이름이 같은 용도의 그릇 ARN 을 가리키지 않는다."
  }

  # 같은 env 이름은 서비스가 달라도 같은 그릇(같은 ARN)이다. auth 가 일곱을
  # 모두 가지므로 auth 를 기준으로 본다.
  assert {
    condition = alltrue(flatten([
      for service, env_arns in output.app_secret_arns : [
        for env_name, arn in env_arns :
        arn == output.app_secret_arns["auth"][env_name]
      ]
    ]))
    error_message = "같은 env 이름이 서비스에 따라 다른 ARN 을 가리킨다."
  }

  # 응답기는 앱과 같은 INTERNAL_API_TOKEN 그릇을 쓴다(K1 — 새 그릇 없음).
  assert {
    condition     = output.media_secret_arns["INTERNAL_API_TOKEN"] == output.app_secret_arns["auth"]["INTERNAL_API_TOKEN"]
    error_message = "media_secret_arns.INTERNAL_API_TOKEN 이 auth 의 INTERNAL_API_TOKEN ARN 과 다르다."
  }
}
