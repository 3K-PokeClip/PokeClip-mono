# R10: 정책 문서 statement 의 actions 가 변수다.
variable "acts" {
  type = list(string)
}

data "aws_iam_policy_document" "media" {
  statement {
    actions   = var.acts
    resources = ["*"]
  }
}
