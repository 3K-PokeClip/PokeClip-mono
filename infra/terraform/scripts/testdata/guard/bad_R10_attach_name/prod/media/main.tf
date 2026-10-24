# R10: 관리형 정책 연결의 주소가 managed 가 아니다.
locals {
  managed_policy_arns = {
    AmazonSSMManagedInstanceCore = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }
}

resource "aws_iam_role_policy_attachment" "extra" {
  for_each = local.managed_policy_arns

  role       = aws_iam_role.media.name
  policy_arn = each.value
}
