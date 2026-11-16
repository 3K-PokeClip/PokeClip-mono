# R10: 상자의 IPv6 IMDS 엔드포인트가 켜진다 — 홉 상한 · 망 칸 실측 밖의 길이
# 열린다(POK-574).
resource "aws_instance" "this" {
  for_each = var.instances

  ami           = "ami-0123456789abcdef0"
  instance_type = "c8gn.large"

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = each.value.cred_relay ? 1 : 2
    http_protocol_ipv6          = "enabled"
  }

  lifecycle {
    ignore_changes = [ami, user_data_base64]
  }
}
