# R10: 관리형 정책 지역값에 SSM Core 밖의 키가 있다.
locals {
  managed_policy_arns = {
    AmazonSSMManagedInstanceCore = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
    Extra                        = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }
}

resource "aws_iam_role_policy_attachment" "managed" {
  for_each = local.managed_policy_arns

  role       = "pokeclip-prod-media"
  policy_arn = each.value
}
