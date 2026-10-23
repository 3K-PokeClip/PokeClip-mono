# R10: rds-db:connect 문의 Resource 가 dbuser ARN 이 아니라 `*` 다.
data "aws_iam_policy_document" "media" {
  statement {
    actions   = ["rds-db:connect"]
    resources = ["*"]
  }
}
