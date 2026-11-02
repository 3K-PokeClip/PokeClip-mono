# R1: 비밀 값 자원은 상태에 값을 남기므로 금지한다.
resource "aws_secretsmanager_secret_version" "signing_key" {
  secret_id     = "example"
  secret_string = "not-a-real-secret"
}
