# media.pokeclip.com 배포(설계 3-3 의 cdn_media · 4-4 · 4-5 · 계획 M10 c5).
#
# 원본은 둘이다 — media-alb(VPC origin → 내부 ALB, https-only 443)와
# rewind-s3(런타임 트랙 rewind 버킷, OAC · Origin Shield 서울). 비헤이비어
# 표는 계획 c5 표로 고정이라 입력으로 받지 않는다. 기본 비헤이비어는
# deny-default 가 404 를 내 원본에 닿지 않는다(설계 3-3 「기본 동작은 전부
# 거절」).
#
# 이 모듈은 기본 provider(us-east-1)로 만든다. VPC origin 서비스 SG 조회와 내부
# ALB SG 인바운드 규칙은 호출하는 루트가 서울 별칭으로 만든다.

locals {
  media_alb_origin_id = "media-alb"
  rewind_origin_id    = "rewind-s3"

  read_methods     = ["GET", "HEAD", "OPTIONS"]
  all_methods      = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
  key_group_ids    = [aws_cloudfront_key_group.this.id]
  live_auth_events = { "viewer-request" = aws_cloudfront_function.live_auth.arn }

  # 우선순위 순서(앞이 먼저). /live/*.m3u8 은 /live/* 보다 앞이어야 한다 — 한
  # 캐시 정책의 최대 TTL 은 원본 Cache-Control 에도 상한이라 두 TTL 을 한
  # 비헤이비어로 만족할 수 없다(설계 4-4).
  ordered_behaviors = [
    {
      path_pattern               = "/api/ingest/*"
      origin_id                  = local.media_alb_origin_id
      allowed_methods            = local.all_methods
      trusted_key_groups         = null
      functions                  = {}
      cache_policy_id            = aws_cloudfront_cache_policy.no_cache.id
      origin_request_policy_id   = aws_cloudfront_origin_request_policy.host_authorization.id
      response_headers_policy_id = null
    },
    {
      path_pattern               = "/live/*.m3u8"
      origin_id                  = local.media_alb_origin_id
      allowed_methods            = local.read_methods
      trusted_key_groups         = local.key_group_ids
      functions                  = local.live_auth_events
      cache_policy_id            = aws_cloudfront_cache_policy.live_playlist.id
      origin_request_policy_id   = aws_cloudfront_origin_request_policy.host_authorization.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.media_cors.id
    },
    {
      path_pattern               = "/live/*"
      origin_id                  = local.media_alb_origin_id
      allowed_methods            = local.read_methods
      trusted_key_groups         = local.key_group_ids
      functions                  = local.live_auth_events
      cache_policy_id            = aws_cloudfront_cache_policy.live_segment.id
      origin_request_policy_id   = aws_cloudfront_origin_request_policy.host_authorization.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.media_cors.id
    },
    # 버킷에는 Authorization 을 넘기지 않는다(OAC 서명과 충돌) — 원본 요청
    # 정책 없음.
    {
      path_pattern               = "/dvr/*"
      origin_id                  = local.rewind_origin_id
      allowed_methods            = local.read_methods
      trusted_key_groups         = local.key_group_ids
      functions                  = {}
      cache_policy_id            = aws_cloudfront_cache_policy.dvr_vod.id
      origin_request_policy_id   = null
      response_headers_policy_id = aws_cloudfront_response_headers_policy.media_cors.id
    },
    {
      path_pattern               = "/vod/*"
      origin_id                  = local.rewind_origin_id
      allowed_methods            = local.read_methods
      trusted_key_groups         = local.key_group_ids
      functions                  = {}
      cache_policy_id            = aws_cloudfront_cache_policy.dvr_vod.id
      origin_request_policy_id   = null
      response_headers_policy_id = aws_cloudfront_response_headers_policy.media_cors.id
    },
    {
      path_pattern               = "/api/streams/*/rewind"
      origin_id                  = local.media_alb_origin_id
      allowed_methods            = local.read_methods
      trusted_key_groups         = null
      functions                  = {}
      cache_policy_id            = aws_cloudfront_cache_policy.rewind_api.id
      origin_request_policy_id   = aws_cloudfront_origin_request_policy.host.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.rewind_cors.id
    },
    {
      path_pattern               = "/playback/*"
      origin_id                  = local.media_alb_origin_id
      allowed_methods            = local.read_methods
      trusted_key_groups         = local.key_group_ids
      functions                  = {}
      cache_policy_id            = aws_cloudfront_cache_policy.no_cache.id
      origin_request_policy_id   = aws_cloudfront_origin_request_policy.playback.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.media_cors.id
    },
  ]

  # 오류 응답 캐싱(배포 단위) — 기본 10초를 1초로. 응답 내용은 바꾸지 않는다.
  error_caching_codes = [404, 500, 502, 503, 504]
}

resource "aws_cloudfront_vpc_origin" "this" {
  vpc_origin_endpoint_config {
    name                   = "${local.name_prefix}-media-internal-alb"
    arn                    = var.internal_alb_arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "https-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

resource "aws_cloudfront_origin_access_control" "rewind" {
  name                              = "${local.name_prefix}-media-rewind"
  description                       = "media: rewind bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "this" {
  enabled    = true
  comment    = "${local.name_prefix} media"
  aliases    = [var.alias]
  web_acl_id = var.web_acl_arn

  # LL-HLS 오리진 설정(설계 5-1 (c)) — 응답 30초 · 연결 3초 · 시도 2회.
  # 내부 ALB 유휴 시간(60초)보다 짧게 두고, 원본 커스텀 헤더는 두지 않는다
  # (비밀은 함수가 Authorization 으로만 싣는다).
  origin {
    origin_id           = local.media_alb_origin_id
    domain_name         = var.internal_alb_dns_name
    connection_timeout  = 3
    connection_attempts = 2

    vpc_origin_config {
      vpc_origin_id       = aws_cloudfront_vpc_origin.this.id
      origin_read_timeout = 30
    }
  }

  origin {
    origin_id                = local.rewind_origin_id
    domain_name              = var.rewind_bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.rewind.id
    connection_timeout       = 3
    connection_attempts      = 2

    origin_shield {
      enabled              = true
      origin_shield_region = "ap-northeast-2"
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.ordered_behaviors
    iterator = behavior

    content {
      path_pattern               = behavior.value.path_pattern
      target_origin_id           = behavior.value.origin_id
      viewer_protocol_policy     = "https-only"
      allowed_methods            = behavior.value.allowed_methods
      cached_methods             = ["GET", "HEAD"]
      trusted_key_groups         = behavior.value.trusted_key_groups
      cache_policy_id            = behavior.value.cache_policy_id
      origin_request_policy_id   = behavior.value.origin_request_policy_id
      response_headers_policy_id = behavior.value.response_headers_policy_id

      dynamic "function_association" {
        for_each = behavior.value.functions

        content {
          event_type   = function_association.key
          function_arn = function_association.value
        }
      }
    }
  }

  # 형식상 원본은 media-alb 지만 deny-default 가 404 를 내 원본에 닿지 않는다.
  # 키 그룹도 둔다(설계 3-3) — 원본 요청 정책은 두지 않는다.
  default_cache_behavior {
    target_origin_id       = local.media_alb_origin_id
    viewer_protocol_policy = "https-only"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    trusted_key_groups     = local.key_group_ids
    cache_policy_id        = aws_cloudfront_cache_policy.no_cache.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.deny_default.arn
    }
  }

  dynamic "custom_error_response" {
    for_each = local.error_caching_codes

    content {
      error_code            = custom_error_response.value
      error_caching_min_ttl = 1
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
