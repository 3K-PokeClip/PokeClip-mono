# R2: KVS 키 자원은 비밀 값을 상태에 남기므로 금지한다.
resource "aws_cloudfrontkeyvaluestore_key" "cdn_secret" {
  key_value_store_arn = "example"
  key                 = "cdn"
  value               = "not-a-real-secret"
}
