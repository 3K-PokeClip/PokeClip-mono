# R10: 역할 인라인 정책을 정책 문서가 아니라 jsonencode 로 적었다.
resource "aws_iam_role_policy" "extra" {
  role = "pokeclip-prod-media"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "s3:*"
      Resource = "*"
    }]
  })
}
