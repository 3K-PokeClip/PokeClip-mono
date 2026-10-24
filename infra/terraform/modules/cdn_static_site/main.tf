# apex 정적 사이트(설계 3-3 cdn_static_site · 계획 M10 c4) — 서울 S3 버킷을
# OAC 로만 읽는 CloudFront 배포. 별칭은 apex · www 이고, www → apex 301 과
# 디렉터리 경로 → index.html 은 viewer-request 함수(apex-request)가 한다.
#
# 모듈은 기본 provider(us-east-1) 하나만 받는다 — 배포 · 함수 · OAC · 응답
# 헤더 정책은 거기에 두고, 버킷과 그 부속 자원은 자원 인자 region 으로 서울에
# 둔다. provider 별칭(configuration_aliases)을 받으면 모듈 단독 validate 가
# 실패한다(hashicorp/terraform#28490). 업로드 역할 · 정책은 두지 않는다
# (결정 52 · 151 — 업로드는 kty IAM 사용자가 한다).

locals {
  # 이름 접두 pokeclip-prod- (엣지 묶음 공유 상수).
  name = "pokeclip-prod-apex"

  origin_id = "apex-s3"

  # 사이트 버킷 리전(서울). 버킷 쪽 다섯 자원이 같은 값을 쓴다.
  bucket_region = "ap-northeast-2"

  # 관리형 캐시 정책 CachingOptimized 의 id — AWS 가 정한 고정값이다(AWS 문서
  # https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-cache-policies.html
  # 2026-10-24 확인). UseOriginCacheControlHeaders 계열은 guard R8 이 막는다.
  caching_optimized_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
}

resource "aws_s3_bucket" "this" {
  region = local.bucket_region

  bucket_prefix = var.bucket_prefix
}

resource "aws_s3_bucket_public_access_block" "this" {
  region = local.bucket_region

  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACL 을 끄고 버킷 소유자가 모든 객체를 소유한다.
resource "aws_s3_bucket_ownership_controls" "this" {
  region = local.bucket_region

  bucket = aws_s3_bucket.this.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  region = local.bucket_region

  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 이 배포만 OAC 로 객체를 읽는다 — 허용문은 이것 하나다.
resource "aws_s3_bucket_policy" "this" {
  region = local.bucket_region

  bucket = aws_s3_bucket.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowCloudFrontOacRead"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.this.arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.this.arn
          }
        }
      },
    ]
  })

  # 퍼블릭 차단 · 소유권 설정을 먼저 걸어 두고 정책을 붙인다. 동시에 걸면
  # S3 가 OperationAborted(409)를 낼 수 있다.
  depends_on = [
    aws_s3_bucket_public_access_block.this,
    aws_s3_bucket_ownership_controls.this,
  ]
}

resource "aws_cloudfront_origin_access_control" "this" {
  name                              = local.name
  description                       = "apex 사이트 버킷 읽기(SigV4)."
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# 원문은 functions/apex-request.js(cloudfront-js-2.0).
resource "aws_cloudfront_function" "apex_request" {
  name    = "${local.name}-request"
  comment = "www -> apex 301 · 디렉터리 경로 -> index.html."
  runtime = "cloudfront-js-2.0"
  code    = file("${path.module}/functions/apex-request.js")
  publish = true
}

# HSTS 만 둔다 — includeSubDomains · preload · CSP 는 두지 않는다.
resource "aws_cloudfront_response_headers_policy" "hsts" {
  name    = "${local.name}-hsts"
  comment = "apex 사이트 HSTS(하위 도메인 · preload 없음)."

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = var.hsts_max_age
      include_subdomains         = false
      preload                    = false
      override                   = true
    }
  }
}

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${local.name} 정적 사이트."
  aliases             = var.domain_names
  default_root_object = "index.html"
  web_acl_id          = var.web_acl_arn

  origin {
    origin_id                = local.origin_id
    domain_name              = aws_s3_bucket.this.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.this.id
  }

  default_cache_behavior {
    target_origin_id           = local.origin_id
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = local.caching_optimized_policy_id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.hsts.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.apex_request.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = var.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}
