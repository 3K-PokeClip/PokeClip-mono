# R10: 고객 관리 정책을 만든다 — 권한 정책은 역할 인라인(정책 문서)으로만 붙인다.
resource "aws_iam_policy" "extra" {
  name = "pokeclip-prod-media-extra"
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = []
  })
}
