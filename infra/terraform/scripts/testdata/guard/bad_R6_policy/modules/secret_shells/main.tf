# R6: 비밀 그릇 모듈에 리소스 정책 자원이 있다(읽기 권한은 읽는 쪽 역할 정책이
# 정한다).
variable "names" {
  type = set(string)
}

resource "aws_secretsmanager_secret" "this" {
  for_each = var.names

  name = each.key
}

resource "aws_secretsmanager_secret_policy" "this" {
  for_each = aws_secretsmanager_secret.this

  secret_arn = each.value.arn
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = []
  })
}
