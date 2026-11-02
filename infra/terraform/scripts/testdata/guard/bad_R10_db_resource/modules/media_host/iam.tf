# R10: rds-db:connect 문의 Resource 가 local.db_users 를 도는 꼴이 아니라 `*` 다.
locals {
  db_users = ["media_indexer", "media_janitor"]
}

data "aws_iam_policy_document" "media" {
  statement {
    actions   = ["rds-db:connect"]
    resources = ["*"]
  }
}
