# R10: 다른 공급자(awscc)의 IAM 자원으로 Media 역할에 Action "*" 를 붙인다 —
# 대상 경로의 자원은 지금 쓰는 유형만(경로 전체 허용 목록).
resource "awscc_iam_role_policy" "extra" {
  role_name   = "pokeclip-prod-media"
  policy_name = "extra"
  policy_document = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "*", Resource = "*" }]
  })
}
