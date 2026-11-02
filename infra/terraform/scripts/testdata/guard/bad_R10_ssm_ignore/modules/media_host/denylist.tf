# R10: 응답기 거부 목록 그릇이 value 를 무시하지 않는다 — 다음 apply 가 운영자가
# 고친 목록을 초기값으로 덮는다.
resource "aws_ssm_parameter" "denylist" {
  name  = "/pokeclip/prod/media/ingest-auth-deny-tokens"
  type  = "String"
  value = "# pokeclip deny-tokens v1"

  lifecycle {
    prevent_destroy = true
  }
}
