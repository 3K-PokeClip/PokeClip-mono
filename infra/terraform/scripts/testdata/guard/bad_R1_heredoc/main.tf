# R1: 하이픈이 든 heredoc 종료자 뒤의 실제 비밀 값 선언도 잡는다.
resource "aws_ssm_parameter" "doc" {
  name  = "example"
  type  = "String"
  value = <<-DOC-TEXT
    본문 속 /* 는 블록 주석이 아니다.
  DOC-TEXT
}

resource "aws_secretsmanager_secret_version" "real" {
  secret_id     = "example"
  secret_string = "example"
}
