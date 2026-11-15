# R10: 상자에 SSH 키 쌍이 달린다(SSM 세션 감사 밖의 접속 신분).
resource "aws_instance" "this" {
  ami           = "ami-0123456789abcdef0"
  instance_type = "c8gn.large"
  key_name      = "pokeclip-ops"

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = each.value.cred_relay ? 1 : 2
    http_protocol_ipv6          = "disabled"
  }
}
