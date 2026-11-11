# 엣지 인가 — 서명 쿠키 키 그룹 · CDN 비밀 KVS · viewer-request 함수 셋.
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

# /dvr 하한(POK-525) — 회차 하한보다 앞 조각을 404 로 막는다. 하한 KVS 는
# 런타임 1단이 만든 그릇(edge_bounds_kvs_arn)이고 이 모듈은 연결만 한다.
# 함수는 스위치와 무관하게 게시한다 — 끔이면 어느 비헤이비어에도 붙지 않는다
# (두 단 apply 의 1단). 게시 코드를 바꾸면 붙은 비헤이비어에 곧바로 실린다
# (PublishFunction) — 붙은 채 고칠 때는 스위치를 먼저 끈다(prod/edge README).
resource "aws_cloudfront_function" "dvr_floor" {
  name                         = "${local.name_prefix}-dvr-floor"
  comment                      = "media: deny /dvr segments below the round floor in KVS"
  runtime                      = "cloudfront-js-2.0"
  code                         = templatefile("${path.module}/functions/dvr-floor.js", { app_origin = var.app_origin })
  key_value_store_associations = [var.edge_bounds_kvs_arn]
  publish                      = true
}

locals {
  # 함수 로그 표지(경보의 지표 필터 패턴) — 원문 상수에서 읽어 한 출처로 둔다.
  # 상수 줄 꼴이 바뀌면 regex 가 plan 에서 멈춘다.
  dvr_floor_log_markers = {
    for key, name in {
      deny          = "DENY_LOG"
      malformed     = "MALFORMED_LOG"
      value_invalid = "VALUE_INVALID_LOG"
      kvs_error     = "KVS_ERROR_LOG"
    } : key => one(regex("(?m)^const ${name} = '([^']+)';$", file("${path.module}/functions/dvr-floor.js")))
  }
}
