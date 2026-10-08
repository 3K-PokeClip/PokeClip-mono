# R1: 유형 라벨을 유니코드 이스케이프(a = a)로 써도 같은 선언이다.
resource "aws_secretsmanager_secret_version" "escaped" {
  secret_id     = "example"
  secret_string = "example"
}
