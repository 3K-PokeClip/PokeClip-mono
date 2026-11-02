# auth 설정 변수(POK-427 · 설계 D8 · D9 · K7) — OAuth 네 덩어리의 CLIENT_ID ·
# REDIRECT_URI 와 프로필 사진 주소가 app_env.auth 로 그대로 가는지, 앱이 부팅
# 때 거부할 값을 plan 에서 먼저 멈추는지 단언한다. 값은 예시다(실제 값은 kty
# 의 tfvars — 커밋하지 않는다). override_data 가 network 원격 상태를 대신하므로
# S3 를 읽지 않는다(AWS 호출 0).

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

# auth_oauth_apps 는 기본값이 없다(D8 — tfvars 필수). 이 파일의 모든 run 에
# 흐르고, run 안의 variables 가 이긴다.
variables {
  auth_oauth_apps = {
    google  = { client_id = "example-google-client-id", redirect_uri = "https://app.pokeclip.com/auth/callback" }
    chzzk   = { client_id = "example-chzzk-client-id", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
    youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
    soop    = { client_id = "example-soop-client-id", redirect_uri = "https://app.pokeclip.com/oauth/soop/callback" }
  }
}

run "passes_oauth_apps_and_photo_url_to_auth_env" {
  command = plan

  assert {
    condition = (
      output.app_env["auth"]["GOOGLE_CLIENT_ID"] == "example-google-client-id" &&
      output.app_env["auth"]["GOOGLE_REDIRECT_URI"] == "https://app.pokeclip.com/auth/callback" &&
      output.app_env["auth"]["CHZZK_CLIENT_ID"] == "example-chzzk-client-id" &&
      output.app_env["auth"]["CHZZK_REDIRECT_URI"] == "https://app.pokeclip.com/oauth/chzzk/callback" &&
      output.app_env["auth"]["YOUTUBE_CLIENT_ID"] == "example-youtube-client-id" &&
      output.app_env["auth"]["YOUTUBE_REDIRECT_URI"] == "https://app.pokeclip.com/oauth/youtube/callback" &&
      output.app_env["auth"]["SOOP_CLIENT_ID"] == "example-soop-client-id" &&
      output.app_env["auth"]["SOOP_REDIRECT_URI"] == "https://app.pokeclip.com/oauth/soop/callback"
    )
    error_message = "app_env.auth 의 OAuth CLIENT_ID · REDIRECT_URI 여덟이 변수 auth_oauth_apps 의 같은 덩어리 값이 아니다."
  }

  # 기본은 같은-오리진 리라이트의 앱 호스트다(K7 · dev 와 같은 꼴).
  assert {
    condition = (
      var.profile_photo_base_url == "https://app.pokeclip.com" &&
      output.app_env["auth"]["PROFILE_PHOTO_BASE_URL"] == "https://app.pokeclip.com"
    )
    error_message = "profile_photo_base_url 기본값 · app_env.auth 의 PROFILE_PHOTO_BASE_URL 이 https://app.pokeclip.com 이 아니다."
  }
}

# 덩어리 하나라도 비면 auth 가 부팅을 거부한다(services README — 한 덩어리 검증).
run "rejects_empty_client_id" {
  command = plan

  variables {
    auth_oauth_apps = {
      google  = { client_id = "example-google-client-id", redirect_uri = "https://app.pokeclip.com/auth/callback" }
      chzzk   = { client_id = "", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
      youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
      soop    = { client_id = "example-soop-client-id", redirect_uri = "https://app.pokeclip.com/oauth/soop/callback" }
    }
  }

  expect_failures = [var.auth_oauth_apps]
}

# 구글은 http 리디렉션을 localhost 에만 허용한다 — 비-localhost 는 https 다.
run "rejects_http_redirect_uri" {
  command = plan

  variables {
    auth_oauth_apps = {
      google  = { client_id = "example-google-client-id", redirect_uri = "http://app.pokeclip.com/auth/callback" }
      chzzk   = { client_id = "example-chzzk-client-id", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
      youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
      soop    = { client_id = "example-soop-client-id", redirect_uri = "https://app.pokeclip.com/oauth/soop/callback" }
    }
  }

  expect_failures = [var.auth_oauth_apps]
}

# prod 리디렉션이 localhost 를 가리키면 동의 뒤 사용자 화면으로 돌아오지 못한다.
run "rejects_localhost_redirect_uri" {
  command = plan

  variables {
    auth_oauth_apps = {
      google  = { client_id = "example-google-client-id", redirect_uri = "https://app.pokeclip.com/auth/callback" }
      chzzk   = { client_id = "example-chzzk-client-id", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
      youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
      soop    = { client_id = "example-soop-client-id", redirect_uri = "https://localhost:3000/oauth/soop/callback" }
    }
  }

  expect_failures = [var.auth_oauth_apps]
}

# 끝의 / 는 주소가 //api/… 가 되어 환경마다 갈린다(PhotoProperties.java).
run "rejects_trailing_slash_photo_base_url" {
  command = plan

  variables {
    profile_photo_base_url = "https://app.pokeclip.com/"
  }

  expect_failures = [var.profile_photo_base_url]
}

# 쿼리 뒤에 경로가 이어 붙어 주소가 깨진다(PhotoProperties.java).
run "rejects_query_on_photo_base_url" {
  command = plan

  variables {
    profile_photo_base_url = "https://app.pokeclip.com?x=1"
  }

  expect_failures = [var.profile_photo_base_url]
}
