# R10: 정책 문서 statement 의 actions 가 concat 식이다.
variable "more_actions" {
  type = list(string)
}

data "aws_iam_policy_document" "media" {
  statement {
    actions   = concat(["s3:GetObject"], var.more_actions)
    resources = ["*"]
  }
}
