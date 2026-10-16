# 소비 루트와의 계약(설계 3-2) — 이름을 바꾸지 않는다.

output "public_key_id" {
  description = "CloudFront 서명 공개키 id — edge 의 키 그룹."
  value       = module.cloudfront_public_key.public_key_id
}

output "signing_key_secret_arn" {
  description = "서명 키(개인키) 비밀 ARN — platform 의 clip secrets · 실행 역할 정책."
  value       = module.secret_shells.secret_arns[local.secret_names["signing-key"]]
}

output "cdn_secret_arn" {
  description = "CDN 비밀 ARN — media 의 인스턴스 역할 정책 · 시작 스크립트."
  value       = module.secret_shells.secret_arns[local.secret_names["cdn-secret"]]
}

output "test_stream_key_secret_arn" {
  description = "시험용 스트림 키 비밀 ARN — media 의 인스턴스 역할 정책 · 루프백 스크립트."
  value       = module.secret_shells.secret_arns[local.secret_names["test-stream-key"]]
}
