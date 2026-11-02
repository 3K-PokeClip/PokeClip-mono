# R8: 관리형 UseOriginCacheControlHeaders 를 이름으로 조회한다(쿠키 전부가 캐시 키).
data "aws_cloudfront_cache_policy" "origin" {
  name = "Managed-UseOriginCacheControlHeaders"
}
