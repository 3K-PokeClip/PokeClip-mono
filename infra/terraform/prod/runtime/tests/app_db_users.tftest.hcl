# 출력 app_db_users(POK-407) — 서버마다 DB 사용자 이름 하나를 platform 이 읽는
# 꼴로 내는지 단언한다(CONTRACT-runtime.md 「런타임 1단 → platform」 행). 이름은
# sql/10_roles.sql 의 CREATE ROLE 줄에서 읽으므로, 여기 기대값이 SQL 과 출력의
# 일치를 함께 잰다. 3번 목록과 SQL 의 일치는 media 의 prod_sql_contract_test.go
# 가 잰다. override_data 가 network 원격 상태를 대신하므로 S3 를 읽지 않는다
# (AWS 호출 0).

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

# 키는 app_env · app_secret_arns 와 같은 서비스 이름이다. platform 은 auth ·
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
    condition     = toset(keys(output.app_db_users)) == toset(keys(output.app_secret_arns))
    error_message = "app_db_users 의 서비스 키가 app_secret_arns 의 서비스 키와 다르다."
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
