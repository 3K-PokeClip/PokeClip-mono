# 엣지 인가 — 서명 쿠키 키 그룹 · CDN 비밀 KVS · viewer-request 함수 둘.
#
# KVS 는 그릇만 만든다. 값(키 cdn-secret = MediaMTX hlsCDNSecret)은
# scripts/kvs-put-cdn-secret.sh 가 넣는다 — KVS 키 자원은 값을 상태에 남기므로
# 두지 않는다(guard R2).

locals {
  name_prefix = "pokeclip-prod"
}

resource "aws_cloudfront_key_group" "this" {
  name    = "${local.name_prefix}-media"
  comment = "media: signed cookie public key"
  items   = [var.public_key_id]
}

resource "aws_cloudfront_key_value_store" "this" {
  name    = "${local.name_prefix}-cdn-secret"
  comment = "media: CDN secret for the live origin (value is put out of band)"
}

# /live 두 비헤이비어 — 시청자 Authorization 을 무시하고 KVS 값으로 덮어쓴다.
# 함수는 연결된 KVS 하나를 cf.kvs() 로 읽는다.
resource "aws_cloudfront_function" "live_auth" {
  name                         = "${local.name_prefix}-live-auth"
  comment                      = "media: set origin Authorization from KVS"
  runtime                      = "cloudfront-js-2.0"
  code                         = file("${path.module}/functions/live-auth.js")
  key_value_store_associations = [aws_cloudfront_key_value_store.this.arn]
  publish                      = true
}

# 기본 비헤이비어 — 원본에 넘기지 않고 404 를 낸다.
resource "aws_cloudfront_function" "deny_default" {
  name    = "${local.name_prefix}-deny-default"
  comment = "media: deny unmatched paths with 404"
  runtime = "cloudfront-js-2.0"
  code    = file("${path.module}/functions/deny-default.js")
  publish = true
}
