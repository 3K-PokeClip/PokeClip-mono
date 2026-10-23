# R10: 상자의 인스턴스 프로필이 같은 경로의 프로필 참조가 아니라 이미 있는 프로필
# 이름 리터럴이다.
resource "aws_instance" "this" {
  ami                  = "ami-0123456789abcdef0"
  instance_type        = "c8gn.large"
  iam_instance_profile = "pokeclip-prod-admin"
}
