# R10: 관리형 정책을 이름으로 조회한다 — ARN 문자열 없이 SSM Core 밖 정책을
# 붙이는 길이다.
data "aws_iam_policy" "admin" {
  name = "AdministratorAccess"
}
