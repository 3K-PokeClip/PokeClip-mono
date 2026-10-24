# 출력 app_db_users(POK-407) — 서버마다 DB 사용자 이름 하나를 platform 이 읽는
# 꼴로 내는지 단언한다(CONTRACT-runtime.md 「런타임 1단 → platform」 행). 이름은
# sql/10_roles.sql 의 CREATE ROLE 줄에서 읽으므로, 여기 기대값이 SQL 과 출력의
# 일치를 함께 잰다. 3번 목록과 SQL 의 일치는 media 의 prod_sql_contract_test.go
# 가 잰다. override_data 가 network 원격 상태를 대신하므로 S3 를 읽지 않는다
# (AWS 호출 0).

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

# 키는 DB 를 쓰는 서비스 넷의 이름이다 — app_env · app_secret_arns 서비스 키의
# 부분집합이다(일꾼 render · upload 는 DB 를 쓰지 않는다). platform 은 auth ·
# clip 만 읽고 모르는 키를 거부하지 않는다 — chat 두 서버 키는 배치(POK-306)가
# 같은 출력에서 읽는다.
run "names_one_db_user_per_server" {
  command = plan

  assert {
    condition = output.app_db_users == {
      auth             = "app_auth"
      clip             = "app_clip"
      "chat-collector" = "app_chat_collector"
      "chat-detector"  = "app_chat_detector"
    }
    error_message = "app_db_users 가 sql/10_roles.sql 의 사용자 넷(app_auth · app_clip · app_chat_collector · app_chat_detector)과 다르다."
  }

  assert {
    condition     = length(setsubtract(keys(output.app_db_users), keys(output.app_secret_arns))) == 0
    error_message = "app_db_users 에 app_secret_arns 에 없는 서비스 키가 있다."
  }
}

# platform remote_state.tf 의 runtime_contract 사전 조건과 같은 정규식이다 —
# 사용자 이름이 rds-db:connect Resource 에 그대로 들어가므로 와일드카드가
# 없어야 한다.
run "matches_platform_user_name_pattern" {
  command = plan

  assert {
    condition = alltrue([
      for user in values(output.app_db_users) :
      can(regex("^[a-z_][a-z0-9_]{0,62}$", user))
    ])
    error_message = "app_db_users 의 값이 platform 의 DB 사용자 이름 꼴(^[a-z_][a-z0-9_]{0,62}$)이 아니다."
  }
}
