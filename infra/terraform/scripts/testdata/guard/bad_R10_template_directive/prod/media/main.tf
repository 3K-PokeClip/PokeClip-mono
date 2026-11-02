# R10: 대상 경로의 문자열에 템플릿 지시자가 있다 — dbuser · rds-db 문자열 대조를
# 비켜 갈 수 있다.
variable "rds_resource_id" {
  type = string
}

locals {
  db_user_arn = "arn:aws:rds-db:ap-northeast-2:${local.account_id}:dbuser:${var.rds_resource_id}/%{if true}*%{endif}"
}
