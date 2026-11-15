# R10: 상자의 IMDS 홉 상한이 리터럴 2 다 — 대리 창구 켬 키에서도 모든 컨테이너가
# IMDS 토큰을 받아 역할 자격을 얻는다(POK-574).
resource "aws_instance" "this" {
  for_each = var.instances

  ami           = "ami-0123456789abcdef0"
  instance_type = "c8gn.large"

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
    http_protocol_ipv6          = "disabled"
  }

  lifecycle {
    ignore_changes = [ami, user_data_base64]
  }
}
