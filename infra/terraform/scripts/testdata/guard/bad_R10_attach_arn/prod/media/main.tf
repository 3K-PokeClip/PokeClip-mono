# R10: 관리형 정책 연결의 policy_arn 이 each.value 가 아니다(변수로 다른 정책을
# 넣을 수 있다).
variable "extra_policy_arn" {
  type = string
}

locals {
  managed_policy_arns = {
    AmazonSSMManagedInstanceCore = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }
}

resource "aws_iam_role_policy_attachment" "managed" {
  for_each = local.managed_policy_arns

  role       = "pokeclip-prod-media"
  policy_arn = var.extra_policy_arn
}
