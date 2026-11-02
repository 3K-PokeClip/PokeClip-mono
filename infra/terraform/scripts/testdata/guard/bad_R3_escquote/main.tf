# R3: 보간 속 \" 가 있어도 SG 블록 깊이를 놓치지 않는다.
resource "aws_security_group" "esc" {
  description = lookup({ Note = "${format("%s", "\"")}" }, "Note")

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
