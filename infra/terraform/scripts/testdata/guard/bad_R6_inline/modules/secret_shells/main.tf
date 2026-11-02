# R6: 비밀 그릇의 인라인 policy 인자도 리소스 정책이다(kty 61차).
variable "names" {
  type = set(string)
}

resource "aws_secretsmanager_secret" "this" {
  for_each = var.names

  name = each.key
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = []
  })
}
