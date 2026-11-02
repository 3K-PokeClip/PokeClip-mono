# R1: count = 0 이라 아무것도 만들지 않아도 선언 자체를 막는다(값을 바꾸는
# 순간 비밀 값이 상태에 남는다).
resource "aws_secretsmanager_secret_version" "disabled" {
  count = 0

  secret_id     = "example"
  secret_string = "example"
}
