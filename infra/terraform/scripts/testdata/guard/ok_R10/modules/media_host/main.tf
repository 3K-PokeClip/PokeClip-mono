# R10 을 지키는 꼴: 떠 있는 상자의 AMI · user_data 는 바꾸지 않는다(설계 5-3).
resource "aws_instance" "this" {
  ami           = "ami-0123456789abcdef0"
  instance_type = "c8gn.large"

  metadata_options {
    http_tokens = "required"
  }

  lifecycle {
    ignore_changes = [ami, user_data_base64]
  }
}
