output "distribution_id" {
  description = "apex 배포 id(랜딩 업로드 뒤 무효화에 쓴다)."
  value       = aws_cloudfront_distribution.this.id
}

output "domain_name" {
  description = "apex 배포 도메인(*.cloudfront.net — 별칭 레코드의 대상)."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "hosted_zone_id" {
  description = "apex 배포의 Route 53 존 id(별칭 레코드의 zone_id)."
  value       = aws_cloudfront_distribution.this.hosted_zone_id
}

output "bucket_name" {
  description = "apex 사이트 버킷 이름(서울 — 랜딩 업로드 대상)."
  value       = aws_s3_bucket.this.bucket
}

output "function_name" {
  description = "apex-request CloudFront Function 이름 — 호출 루트가 함수 로그 그룹 /aws/cloudfront/function/<이름> 을 짓는다."
  value       = aws_cloudfront_function.apex_request.name
}
