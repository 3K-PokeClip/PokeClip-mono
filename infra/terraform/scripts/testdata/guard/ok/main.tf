# 모든 규칙을 통과하는 형태: 비밀 그릇만(값 없음) · SG 규칙은 별도 자원.
resource "aws_secretsmanager_secret" "signing_key" {
  name = "example"
}

resource "aws_security_group" "separate" {
  name        = "example"
  description = "example"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.separate.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.separate.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
