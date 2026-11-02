# R10: 허용 유형(aws_ssm_parameter) 안에 provisioner 가 있다(apply 하는 기계의
# 자격으로 임의 명령을 돌린다).
resource "aws_ssm_parameter" "denylist" {
  name  = "/pokeclip/prod/media/denylist"
  type  = "String"
  value = "# pokeclip deny-tokens v1"

  provisioner "local-exec" {
    command = "echo x"
  }
}
