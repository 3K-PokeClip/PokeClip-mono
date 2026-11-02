# R10: DB 사용자 지역값이 두 이름(media_indexer · media_janitor)이 아니라
# 와일드카드다 — 같은 for 식이 모든 사용자를 연다.
variable "rds_resource_id" {
  type = string
}

locals {
  db_users = ["*"]
}

data "aws_iam_policy_document" "media" {
  statement {
    actions = ["rds-db:connect"]
    resources = [
      for u in local.db_users :
      "arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.rds_resource_id}/${u}"
    ]
  }
}
