# 앱 서버 DB 사용자 이름(POK-407) — platform 이 태스크 env POSTGRES_USER 와
# rds-db:connect 의 사용자 자리에 넣는다(CONTRACT-runtime.md 「런타임 1단 →
# platform」 행). 사용자 · rds_iam · GRANT 는 Terraform 이 아니라
# sql/10_roles.sql · 30_grants.sql 이 만든다.
#
# 이름의 원본은 sql/10_roles.sql 의 CREATE ROLE 줄 하나다. 출력이 그 줄을 읽어
# SQL 과 출력이 갈라지지 않는다. 키는 app_env · app_secret_arns 와 같은 서비스
# 이름이다(app_chat_collector → chat-collector).

locals {
  # media 의 prod_sql_contract_test.go 가 같은 꼴의 정규식(appUserCreate)으로
  # 이 줄을 읽는다 — 줄 꼴을 바꾸면 둘을 함께 고친다.
  app_db_user_names = flatten(regexall(
    "(?m)^\\s+CREATE ROLE (app_[a-z0-9_]+) LOGIN;$",
    file("${path.module}/sql/10_roles.sql"),
  ))

  app_db_users = {
    for name in local.app_db_user_names :
    replace(trimprefix(name, "app_"), "_", "-") => name
  }
}

output "app_db_users" {
  description = "서비스 → DB 사용자 이름 — platform 의 POSTGRES_USER · rds-db:connect 사용자 자리."
  value       = local.app_db_users

  # platform remote_state.tf 의 runtime_contract 사전 조건과 같은 꼴이다. SQL 의
  # 줄 꼴이 바뀌어 이름을 못 읽으면 platform 이 아니라 여기서 멈춘다.
  precondition {
    condition = alltrue([
      for service in ["auth", "clip"] :
      can(regex("^[a-z_][a-z0-9_]{0,62}$", local.app_db_users[service]))
    ])
    error_message = "sql/10_roles.sql 에서 auth · clip 의 DB 사용자 이름을 읽지 못했다(CREATE ROLE app_… LOGIN; 줄 꼴 확인)."
  }
}
