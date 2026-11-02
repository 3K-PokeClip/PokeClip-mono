# 비밀 그릇 모듈: 입력은 names 뿐 · 그릇만(값 · 리소스 정책 없음).
variable "names" {
  type = set(string)
}

resource "aws_secretsmanager_secret" "this" {
  for_each = var.names

  name = each.key
}

output "secret_arns" {
  value = { for name, secret in aws_secretsmanager_secret.this : name => secret.arn }
}
