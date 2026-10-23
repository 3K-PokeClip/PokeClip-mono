# R10: rds-db:connect Resource 의 DB 사용자 자리가 와일드카드다.
data "aws_caller_identity" "current" {}

variable "rds_resource_id" {
  type = string
}

data "aws_iam_policy_document" "media" {
  statement {
    actions = ["rds-db:connect"]
    resources = [
      "arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/*",
    ]
  }
}
