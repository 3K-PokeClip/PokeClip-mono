# R10: Media 상자가 user_data 변경을 무시하지 않는다 — 구성이 바뀌면 떠 있는
# 상자가 교체된다(설계 5-3 은 새 키로만).
resource "aws_instance" "this" {
  ami           = "ami-0123456789abcdef0"
  instance_type = "c8gn.large"

  metadata_options {
    http_tokens = "required"
  }

  lifecycle {
    ignore_changes = [ami]
  }
}
