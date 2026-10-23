# R10 을 지키는 꼴: 응답기 거부 목록 그릇은 지워지지 않고 value 를 무시한다.
resource "aws_ssm_parameter" "denylist" {
  name  = "/pokeclip/prod/media/ingest-auth-deny-tokens"
  type  = "String"
  value = "# pokeclip deny-tokens v1"

  lifecycle {
    ignore_changes  = [value]
    prevent_destroy = true
  }
}
