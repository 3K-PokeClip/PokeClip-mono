# 소비 루트와의 계약(설계 3-2) — 이름을 바꾸지 않는다. records · 런타임
# 트랙 2단 · KVS 값 입력 · kty 로컬 랜딩 업로드가 쓴다.

output "apex_bucket_name" {
  description = "apex 사이트 버킷 이름 — kty 로컬 랜딩 업로드 대상."
  value       = module.apex.bucket_name
}

output "apex_distribution_id" {
  description = "apex 배포 id — 랜딩 업로드 뒤 무효화."
  value       = module.apex.distribution_id
}

output "apex_distribution_domain" {
  description = "apex 배포 도메인(d….cloudfront.net) — records 의 apex · www 별칭 대상."
  value       = module.apex.domain_name
}

output "apex_distribution_zone_id" {
  description = "apex 배포의 Route 53 별칭 존 id — records 의 apex · www 별칭."
  value       = module.apex.hosted_zone_id
}

output "media_distribution_arn" {
  description = "media 배포 ARN — 런타임 트랙 2단의 rewind 버킷 OAC 허용문(AWS:SourceArn)."
  value       = module.media.distribution_arn
}

output "media_distribution_domain" {
  description = "media 배포 도메인(d….cloudfront.net) — records 의 media 별칭 대상."
  value       = module.media.domain_name
}

output "media_distribution_zone_id" {
  description = "media 배포의 Route 53 별칭 존 id — records 의 media 별칭."
  value       = module.media.hosted_zone_id
}

output "cdn_kvs_arn" {
  description = "CDN 비밀 KVS ARN — scripts/kvs-put-cdn-secret.sh 의 --kvs-arn."
  value       = module.media.kvs_arn
}
