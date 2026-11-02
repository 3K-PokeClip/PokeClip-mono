# R6: 입력은 names 하나뿐이다 — 읽기 역할 같은 다른 입력은 두지 않는다.
variable "names" {
  type = set(string)
}

variable "reader_role_arns" {
  type = list(string)
}

resource "aws_secretsmanager_secret" "this" {
  for_each = var.names

  name = each.key
}
