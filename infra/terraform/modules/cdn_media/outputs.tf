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

output "function_names" {
  description = "CloudFront Function 이름(live_auth · deny_default · dvr_floor) — 호출 루트가 함수 로그 그룹 /aws/cloudfront/function/<이름> 을 짓는다."
  value = {
    live_auth    = aws_cloudfront_function.live_auth.name
    deny_default = aws_cloudfront_function.deny_default.name
    dvr_floor    = aws_cloudfront_function.dvr_floor.name
  }
}

# 배포의 /dvr/* 비헤이비어에서 읽는다 — 호출 루트 시험이 스위치가 전달됐는지
# 본다(루트 시험은 모듈 안 자원을 주소로 볼 수 없다).
output "dvr_floor_attached" {
  description = "/dvr/* 비헤이비어에 dvr-floor 가 viewer-request 로 붙었는가(스위치 dvr_floor_enabled 의 결과)."
  value = anytrue([
    for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : anytrue([
      for association in behavior.function_association :
      association.event_type == "viewer-request" && association.function_arn == aws_cloudfront_function.dvr_floor.arn
    ]) if behavior.path_pattern == "/dvr/*"
  ])
}

output "dvr_floor_log_markers" {
  description = "dvr-floor 함수 로그 줄의 고정 머리(deny · malformed · value_invalid · kvs_error) — functions/dvr-floor.js 상수에서 읽는다. 호출 루트의 지표 필터 패턴이 이 값을 쓴다."
  value       = local.dvr_floor_log_markers
}
