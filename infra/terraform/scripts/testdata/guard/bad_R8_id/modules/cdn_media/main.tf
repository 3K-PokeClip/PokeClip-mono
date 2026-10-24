# R8: 비헤이비어가 관리형 UseOriginCacheControlHeaders 의 id 를 리터럴로 쓴다.
resource "aws_cloudfront_distribution" "this" {
  enabled = true

  default_cache_behavior {
    target_origin_id       = "media-alb"
    viewer_protocol_policy = "https-only"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = "83da9c7e-98b4-4e11-a168-04f0df8e2c65"
  }
}
