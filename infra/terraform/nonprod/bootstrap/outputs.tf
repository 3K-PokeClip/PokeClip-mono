output "state_bucket_name" {
  description = "nonprod 상태 버킷 이름 — 각 루트 backend.hcl 의 bucket 값."
  value       = module.state_bucket.bucket_name
}

output "state_bucket_arn" {
  description = "nonprod 상태 버킷 ARN."
  value       = module.state_bucket.bucket_arn
}
