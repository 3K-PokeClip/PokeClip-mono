# R0: 심볼릭 링크 디렉터리도 금지다(링크 너머 .tf 가 검사에서 빠진다).
resource "aws_secretsmanager_secret" "ok" {
  name = "example"
}
