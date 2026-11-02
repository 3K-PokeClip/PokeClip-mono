# R11: dev 상자의 EIP 선언이 없다(EC2 · SG 는 삭제 방지).
resource "aws_instance" "dev" {
  ami           = "ami-00000000"
  instance_type = "t3.small"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_security_group" "dev" {
  description = "example"

  lifecycle {
    prevent_destroy = true
  }
}
