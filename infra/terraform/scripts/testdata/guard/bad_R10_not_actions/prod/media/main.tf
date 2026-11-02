# R10: 정책 문서 statement 가 not_actions 로 「이것 빼고 전부」 를 허용한다.
data "aws_iam_policy_document" "media" {
  statement {
    not_actions = ["iam:*"]
    resources   = ["*"]
  }
}
