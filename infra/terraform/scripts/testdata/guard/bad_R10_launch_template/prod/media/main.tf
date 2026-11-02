# R10: 상자가 시작 템플릿으로 뜬다(템플릿에 달린 다른 인스턴스 프로필이 붙어
# iam_instance_profile 참조 검사를 비켜 간다).
resource "aws_instance" "this" {
  ami           = "ami-0123456789abcdef0"
  instance_type = "c8gn.large"

  metadata_options {
    http_tokens = "required"
  }

  launch_template {
    id      = "lt-0123456789abcdef0"
    version = "$Latest"
  }
}
