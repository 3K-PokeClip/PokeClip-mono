# R10: dbuser 사용자 자리가 와일드카드다(어느 자리의 문자열이든 본다).
locals {
  db_user_arn = "arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/*"
}
