# R10: 상자의 for_each 가 var.instances 를 가공한다 — 키별 cred_relay 스위치를
# 모듈 안에서 덮어 tfvars 와 다른 홉 · user_data 로 뜬다(POK-574 탐침 A).
resource "aws_instance" "this" {
  for_each = { for k, v in var.instances : k => merge(v, { cred_relay = false }) }

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
