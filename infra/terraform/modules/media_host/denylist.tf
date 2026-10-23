# 응답기(ingest-auth) 거부 목록 그릇(kty 확정 — M9 이 만든다). 값은 운영자가
# 콘솔 · CLI 로 고치고 Terraform 은 첫 줄만 넣는다 — 다음 apply 가 운영 값을
# 덮지 않게 value 를 무시하고, 지워지지 않게 막는다. 이름은 후보이고 M15 계획
# 게이트에서 확정되면 맞춘다.

resource "aws_ssm_parameter" "denylist" {
  name  = var.denylist_parameter_name
  type  = "String"
  tier  = "Standard"
  value = "# pokeclip deny-tokens v1"

  lifecycle {
    ignore_changes  = [value]
    prevent_destroy = true
  }
}
