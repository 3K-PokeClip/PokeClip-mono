# R10: 관리형 정책 연결의 role 이 같은 경로의 역할 참조가 아니라 다른 서비스 역할
# 이름 리터럴이다.
locals {
  managed_policy_arns = {
    AmazonSSMManagedInstanceCore = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }
}

resource "aws_iam_role_policy_attachment" "managed" {
  for_each = local.managed_policy_arns

  role       = "pokeclip-prod-auth-task"
  policy_arn = each.value
}
