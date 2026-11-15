# R10: 상자가 IMDSv1 을 연다(http_tokens 가 required 가 아님 — SSRF 로 역할
# 자격이 샌다).
resource "aws_instance" "this" {
  ami           = "ami-0123456789abcdef0"
  instance_type = "c8gn.large"

  metadata_options {
    http_tokens                 = "optional"
    http_put_response_hop_limit = each.value.cred_relay ? 1 : 2
    http_protocol_ipv6          = "disabled"
  }
}
