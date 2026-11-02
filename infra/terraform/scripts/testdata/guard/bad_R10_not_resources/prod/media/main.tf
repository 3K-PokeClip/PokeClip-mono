# R10: 정책 문서 statement 가 not_resources 로 「이것 빼고 전부」 를 겨눈다.
data "aws_iam_policy_document" "media" {
  statement {
    actions       = ["s3:GetObject"]
    not_resources = ["arn:aws:s3:::pokeclip-prod-other/*"]
  }
}
