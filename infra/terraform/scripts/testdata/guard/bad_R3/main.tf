# R3: SG 규칙은 별도 자원으로만 만든다. 인라인 ingress · egress 는 금지한다.
resource "aws_security_group" "inline" {
  name = "example"

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
