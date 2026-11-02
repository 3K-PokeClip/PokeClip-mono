# R8 통과: 다른 관리형 캐시 정책은 리터럴 이름으로 조회해도 된다.
data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}
