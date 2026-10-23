# R10: dbuser 사용자 자리에 for 변수가 아닌 다른 변수가 온다(var.u = "*" 이면
# 모든 사용자).
variable "rds_resource_id" {
  type = string
}

variable "u" {
  type = string
}

locals {
  db_users = ["media_indexer", "media_janitor"]
}

data "aws_iam_policy_document" "media" {
  statement {
    actions = ["rds-db:connect"]
    resources = [
      for u in local.db_users :
      "arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.rds_resource_id}/${var.u}"
    ]
  }
}
