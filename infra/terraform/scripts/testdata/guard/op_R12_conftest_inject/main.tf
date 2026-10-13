# 공개 존 루트(nonprod/dns-legacy)가 없는 트리. 운영 표지면 R12 ① 위반이다.
resource "aws_secretsmanager_secret" "example" {
  name = "example"
}
