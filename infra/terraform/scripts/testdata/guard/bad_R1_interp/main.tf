# R1: 보간 속 따옴표 · # · << 는 문자열이다. 뒤의 실제 비밀 값 선언을 지우지 않는다.
locals {
  note = "doc: ${format("<<DOC-TEXT # %s", "example")}"
}

resource "aws_secretsmanager_secret_version" "real" {
  secret_id     = "example"
  secret_string = <<-DOC-TEXT
    example
  DOC-TEXT
}
