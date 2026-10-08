# dev 의 EC2 · EIP · SG 는 모두 삭제 방지. 규칙 자원은 대상이 아니다.
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

resource "aws_vpc_security_group_ingress_rule" "dev" {
  security_group_id = aws_security_group.dev.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}
