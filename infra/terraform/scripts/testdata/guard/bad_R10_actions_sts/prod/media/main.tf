# R10: 정책 문서 statement 에 sts 동작이 있다(다른 역할을 넘겨받는다).
data "aws_iam_policy_document" "media" {
  statement {
    actions   = ["sts:AssumeRole"]
    resources = ["*"]
  }
}
