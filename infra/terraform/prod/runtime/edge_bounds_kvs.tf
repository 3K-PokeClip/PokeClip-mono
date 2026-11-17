# /dvr 하한 KVS 그릇(POK-525) — 열린 회차의 하한 사본이다. 사이드카 Writer 가
# 원장 표 stream_edge_bounds(정본 — prod/runtime/sql/)에서 올린 값을 키
# {스트림}/{회차} · 값 10진 MSN 으로 쓰고, 회차가 닫히면 키를 지운다. edge 의
# /dvr 하한 함수 dvr-floor 가 이 그릇을 늘 연결하고, edge 스위치
# dvr_floor_enabled 가 참일 때만 /dvr/* 에 붙어 읽는다(CONTRACT-runtime.md
# 「런타임 1단 → media · edge」).
#
# 그릇만 만든다. 키 · 값은 Writer 가 UpdateKeys 로 쓴다 — KVS 키 자원은 값을
# 상태에 남기고 Writer 의 판(ETag)과 겨루므로 두지 않는다(guard R2). 쓰기
# 권한(EdgeBoundsKvs)은 media 인스턴스 역할의 신원 정책이다(modules/media_host).
# 이름을 바꾸면 새 그릇(새 ARN)이 생기므로 시험이 이름을 고정한다.

resource "aws_cloudfront_key_value_store" "edge_bounds" {
  name    = "pokeclip-prod-edge-bounds"
  comment = "media: /dvr round floors (values are written by the media sidecar)"
}

output "edge_bounds_kvs_arn" {
  description = "/dvr 하한 KVS ARN(media — 인스턴스 역할 EdgeBoundsKvs 의 Resource · 사이드카 REWIND_EDGE_BOUNDS_KVS_ARN, edge — /dvr 함수의 KVS 연결)."
  value       = aws_cloudfront_key_value_store.edge_bounds.arn
}
