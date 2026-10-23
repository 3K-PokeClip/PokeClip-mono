# R10: rds-db 동작을 정책 문서 statement 밖(jsonencode 인라인 정책)에 적었다 —
# Resource 를 대조할 수 없다.
resource "aws_iam_role_policy" "db" {
  role = "pokeclip-prod-media"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "rds-db:connect"
      Resource = "*"
    }]
  })
}
