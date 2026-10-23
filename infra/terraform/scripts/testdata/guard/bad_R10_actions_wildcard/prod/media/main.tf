# R10: 정책 문서 statement 의 동작이 와일드카드다.
data "aws_iam_policy_document" "media" {
  statement {
    actions   = ["*"]
    resources = ["*"]
  }
}
