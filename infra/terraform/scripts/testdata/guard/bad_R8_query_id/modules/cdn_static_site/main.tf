# R8: 짝 정책 UseOriginCacheControlHeaders-QueryStrings 를 id 로 조회한다.
data "aws_cloudfront_cache_policy" "origin" {
  id = "4cc15a8a-d715-48a4-82b8-cc0b614638fe"
}
