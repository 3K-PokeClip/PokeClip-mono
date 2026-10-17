output "state_bucket_name" {
  description = "prod 상태 버킷 이름 — prod 각 루트 backend.hcl 의 bucket 값."
  value       = module.state_bucket.bucket_name
}

output "state_bucket_arn" {
  description = "prod 상태 버킷 ARN."
  value       = module.state_bucket.bucket_arn
}
