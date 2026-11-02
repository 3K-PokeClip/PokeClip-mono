# R0: 검사 트리 안의 conftest 설정 파일(conftest.toml)만 어긴다.
resource "aws_secretsmanager_secret" "example" {
  name = "example"
}
