# R3: 인라인 egress 도 금지한다(SG 를 여러 줄에 걸쳐 쓴 꼴).
resource "aws_security_group" "inline_egress" {
  name = "example"
  tags = {
    Name = "example"
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
