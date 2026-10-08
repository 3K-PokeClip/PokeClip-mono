# R1: 비밀 값 조회 data source 도 상태에 값을 남기므로 금지한다.
data "aws_secretsmanager_secret_version" "signing_key" {
  secret_id = "example"
}
