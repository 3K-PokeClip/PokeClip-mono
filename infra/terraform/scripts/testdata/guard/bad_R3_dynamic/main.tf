# R3: dynamic 블록으로 만든 인라인 규칙도 인라인 규칙이다.
resource "aws_security_group" "dyn" {
  name = "example"

  dynamic "ingress" {
    for_each = [443]
    content {
      from_port   = ingress.value
      to_port     = ingress.value
      protocol    = "tcp"
      cidr_blocks = ["0.0.0.0/0"]
    }
  }
}
