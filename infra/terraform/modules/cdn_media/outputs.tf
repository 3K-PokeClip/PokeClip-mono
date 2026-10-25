output "distribution_arn" {
  description = "media 배포 ARN(런타임 트랙 2단의 rewind 버킷 OAC 정책 · 표준 로그)."
  value       = aws_cloudfront_distribution.this.arn
}

output "distribution_id" {
  description = "media 배포 id."
  value       = aws_cloudfront_distribution.this.id
}

output "domain_name" {
  description = "media 배포 도메인(*.cloudfront.net — records 의 별칭 대상)."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "hosted_zone_id" {
  description = "media 배포의 Route 53 별칭 존 id(records 의 별칭 zone_id)."
  value       = aws_cloudfront_distribution.this.hosted_zone_id
}

output "kvs_arn" {
  description = "CDN 비밀 KVS ARN(scripts/kvs-put-cdn-secret.sh 의 --kvs-arn)."
  value       = aws_cloudfront_key_value_store.this.arn
}

output "vpc_origin_id" {
  description = "내부 ALB VPC origin id. 호출하는 루트가 이 VPC origin 이 생긴 뒤 서비스 SG(CloudFront-VPCOrigins-Service-SG)를 조회해 내부 ALB SG 인바운드 규칙을 만든다."
  value       = aws_cloudfront_vpc_origin.this.id
}
