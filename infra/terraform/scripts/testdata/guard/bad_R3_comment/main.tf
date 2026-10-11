# R3: 주석 속 닫는 중괄호가 블록 끝으로 세어지면 뒤의 ingress 를 놓친다.
resource "aws_security_group" "comment_brace" {
  name = "example"
  # }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
