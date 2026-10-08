# R11: 보간 속 \" 가 있는 무보호 EIP — 같은 유형 보호 자원에 묻히지 않는다.
resource "aws_instance" "dev" {
  ami           = "ami-00000000"
  instance_type = "t3.small"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_eip" "kept" {
  domain = "vpc"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_eip" "dev" {
  domain = lookup({ Note = "${format("%s", "\"")}" }, "Note")
}

resource "aws_security_group" "dev" {
  description = "example"

  lifecycle {
    prevent_destroy = true
  }
}
