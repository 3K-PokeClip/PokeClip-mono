# R10: 응답기 거부 목록 그릇에 prevent_destroy = true 가 없다.
resource "aws_ssm_parameter" "denylist" {
  name  = "/pokeclip/prod/media/ingest-auth-deny-tokens"
  type  = "String"
  value = "# pokeclip deny-tokens v1"

  lifecycle {
    ignore_changes = [value]
  }
}
