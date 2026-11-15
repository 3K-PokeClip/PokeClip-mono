# R10 을 지키는 꼴: 떠 있는 상자의 AMI · user_data 는 바꾸지 않는다(설계 5-3).
resource "aws_instance" "this" {
  ami           = "ami-0123456789abcdef0"
  instance_type = "c8gn.large"

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = each.value.cred_relay ? 1 : 2
    http_protocol_ipv6          = "disabled"
  }

  lifecycle {
    ignore_changes = [ami, user_data_base64]
  }
}
