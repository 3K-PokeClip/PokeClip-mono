# R10: 정책 문서 statement 의 actions 가 지역값이다 — 식이라 rds-db:connect 를
# 덮는지 대조할 수 없다.
locals {
  acts = ["*"]
}

data "aws_iam_policy_document" "media" {
  statement {
    actions   = local.acts
    resources = ["*"]
  }
}
