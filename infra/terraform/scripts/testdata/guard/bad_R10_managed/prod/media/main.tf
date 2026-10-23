# R10: Media 인스턴스 역할에 SSM Core 밖의 관리형 정책을 붙였다.
resource "aws_iam_role_policy_attachment" "admin" {
  role       = "pokeclip-prod-media"
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}
