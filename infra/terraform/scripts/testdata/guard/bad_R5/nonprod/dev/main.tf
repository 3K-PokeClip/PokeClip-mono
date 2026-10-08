# R5: dev EIP 에 삭제 방지가 없다(EC2 · SG 에는 있다).
resource "aws_instance" "dev" {
  ami           = "ami-00000000"
  instance_type = "t3.small"

  lifecycle {
    prevent_destroy = true
    ignore_changes  = [ami, user_data]
  }
}

resource "aws_eip" "dev" {
  domain   = "vpc"
  instance = aws_instance.dev.id
}

resource "aws_security_group" "dev" {
  description = "example"

  lifecycle {
    prevent_destroy = true
  }
}
