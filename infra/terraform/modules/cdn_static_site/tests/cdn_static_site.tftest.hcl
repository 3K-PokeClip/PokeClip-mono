# apex 정적 사이트 모듈(계획 M10 c3 · 설계 3-3 cdn_static_site)을 mock
# provider 로 단언한다 — S3 버킷(서울) 보호 설정 · OAC 허용문 하나뿐인 버킷
# 정책 · OAC · 별칭 apex · www · us-east-1 인증서 · CLOUDFRONT WAF ·
# viewer-request = apex-request · index.html · IPv6 · HSTS 응답 헤더 정책
# (includeSubDomains · preload · CSP 없음) · redirect-to-https.
#
# 모듈은 기본 provider(us-east-1) 하나만 받는다. 버킷과 그 부속 자원은 자원
# 인자 region 으로 서울에 둔다. mock 은 region 을 설정하지 않은 자원에
# provider 기본 리전(us-east-1)을 주므로, 버킷 쪽 자원에서 region 이 빠지면
# 실패한다. 계정 ID · ARN · id 는 모두 가짜 값이다.

mock_provider "aws" {
  mock_resource "aws_s3_bucket" {
    override_during = plan

    defaults = {
      id                          = "pokeclip-prod-apex-example"
      bucket                      = "pokeclip-prod-apex-example"
      arn                         = "arn:aws:s3:::pokeclip-prod-apex-example"
      region                      = "us-east-1"
      bucket_regional_domain_name = "pokeclip-prod-apex-example.s3.ap-northeast-2.amazonaws.com"
    }
  }

  mock_resource "aws_s3_bucket_public_access_block" {
    override_during = plan

    defaults = {
      region = "us-east-1"
    }
  }

  mock_resource "aws_s3_bucket_ownership_controls" {
    override_during = plan

    defaults = {
      region = "us-east-1"
    }
  }

  mock_resource "aws_s3_bucket_server_side_encryption_configuration" {
    override_during = plan

    defaults = {
      region = "us-east-1"
    }
  }

  mock_resource "aws_s3_bucket_policy" {
    override_during = plan

    defaults = {
      region = "us-east-1"
    }
  }

  mock_resource "aws_cloudfront_distribution" {
    override_during = plan

    defaults = {
      id             = "E0EXAMPLEAPEX"
      arn            = "arn:aws:cloudfront::111122223333:distribution/E0EXAMPLEAPEX"
      domain_name    = "d0example.cloudfront.net"
      hosted_zone_id = "Z2FDTNDATAQYW2"
    }
  }

  mock_resource "aws_cloudfront_origin_access_control" {
    override_during = plan

    defaults = {
      id = "E0EXAMPLEOAC"
    }
  }

  mock_resource "aws_cloudfront_function" {
    override_during = plan

    defaults = {
      arn = "arn:aws:cloudfront::111122223333:function/pokeclip-prod-apex-request"
    }
  }

  mock_resource "aws_cloudfront_response_headers_policy" {
    override_during = plan

    defaults = {
      id = "00000000-0000-0000-0000-00000000hsts"
    }
  }
}

variables {
  domain_names    = ["pokeclip.com", "www.pokeclip.com"]
  certificate_arn = "arn:aws:acm:us-east-1:111122223333:certificate/example-use1"
  web_acl_arn     = "arn:aws:wafv2:us-east-1:111122223333:global/webacl/pokeclip-prod-cloudfront/0example"
}

run "creates_protected_bucket_in_seoul" {
  command = plan

  assert {
    condition = alltrue([
      aws_s3_bucket.this.region == "ap-northeast-2",
      aws_s3_bucket_public_access_block.this.region == "ap-northeast-2",
      aws_s3_bucket_ownership_controls.this.region == "ap-northeast-2",
      aws_s3_bucket_server_side_encryption_configuration.this.region == "ap-northeast-2",
      aws_s3_bucket_policy.this.region == "ap-northeast-2",
    ])
    error_message = "버킷 · 퍼블릭 차단 · 소유권 · 암호화 · 정책 중 region 이 서울(ap-northeast-2)이 아닌 것이 있다."
  }

  assert {
    condition     = aws_s3_bucket.this.bucket_prefix == "pokeclip-prod-apex-"
    error_message = "버킷 이름 접두의 기본값이 pokeclip-prod-apex- 가 아니다."
  }

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.this.bucket == "pokeclip-prod-apex-example",
      aws_s3_bucket_public_access_block.this.block_public_acls,
      aws_s3_bucket_public_access_block.this.block_public_policy,
      aws_s3_bucket_public_access_block.this.ignore_public_acls,
      aws_s3_bucket_public_access_block.this.restrict_public_buckets,
    ])
    error_message = "서울 버킷의 퍼블릭 차단 네 항목이 모두 true 가 아니다."
  }

  assert {
    condition = (
      aws_s3_bucket_ownership_controls.this.bucket == "pokeclip-prod-apex-example" &&
      one([
        for rule in aws_s3_bucket_ownership_controls.this.rule : rule.object_ownership
      ]) == "BucketOwnerEnforced"
    )
    error_message = "서울 버킷의 객체 소유권이 BucketOwnerEnforced(ACL 끔)가 아니다."
  }

  assert {
    condition = (
      aws_s3_bucket_server_side_encryption_configuration.this.bucket == "pokeclip-prod-apex-example" &&
      one([
        for rule in aws_s3_bucket_server_side_encryption_configuration.this.rule :
        rule.apply_server_side_encryption_by_default[0].sse_algorithm
      ]) == "AES256"
    )
    error_message = "서울 버킷의 기본 암호화가 SSE-S3(AES256)가 아니다."
  }
}

run "uses_bucket_prefix_input" {
  command = plan

  variables {
    bucket_prefix = "example-apex-"
  }

  assert {
    condition     = aws_s3_bucket.this.bucket_prefix == "example-apex-"
    error_message = "버킷 이름 접두가 입력 bucket_prefix 가 아니다."
  }
}

run "bucket_policy_allows_only_this_distribution_through_oac" {
  command = plan

  assert {
    condition     = aws_s3_bucket_policy.this.bucket == "pokeclip-prod-apex-example"
    error_message = "버킷 정책이 서울 버킷에 붙지 않았다."
  }

  assert {
    condition = jsondecode(aws_s3_bucket_policy.this.policy) == jsondecode(jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Sid       = "AllowCloudFrontOacRead"
          Effect    = "Allow"
          Principal = { Service = "cloudfront.amazonaws.com" }
          Action    = "s3:GetObject"
          Resource  = "arn:aws:s3:::pokeclip-prod-apex-example/*"
          Condition = {
            StringEquals = {
              "AWS:SourceArn" = "arn:aws:cloudfront::111122223333:distribution/E0EXAMPLEAPEX"
            }
          }
        },
      ]
    }))
    error_message = "버킷 정책이 이 배포만 OAC 로 읽게 하는 허용문 하나가 아니다."
  }
}

run "signs_s3_origin_with_oac" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_origin_access_control.this.origin_access_control_origin_type == "s3" &&
      aws_cloudfront_origin_access_control.this.signing_behavior == "always" &&
      aws_cloudfront_origin_access_control.this.signing_protocol == "sigv4"
    )
    error_message = "OAC 가 s3 · always · sigv4 가 아니다."
  }

  assert {
    condition = alltrue([
      length(aws_cloudfront_distribution.this.origin) == 1,
      one(aws_cloudfront_distribution.this.origin).domain_name == "pokeclip-prod-apex-example.s3.ap-northeast-2.amazonaws.com",
      one(aws_cloudfront_distribution.this.origin).origin_access_control_id == "E0EXAMPLEOAC",
    ])
    error_message = "배포 원본이 서울 버킷(지역 도메인) 하나와 이 OAC 가 아니다."
  }

  assert {
    condition = (
      one(aws_cloudfront_distribution.this.default_cache_behavior).target_origin_id ==
      one(aws_cloudfront_distribution.this.origin).origin_id
    )
    error_message = "기본 비헤이비어가 버킷 원본을 가리키지 않는다."
  }
}

run "serves_aliases_with_use1_certificate_and_cloudfront_waf" {
  command = plan

  assert {
    condition     = aws_cloudfront_distribution.this.aliases == toset(["pokeclip.com", "www.pokeclip.com"])
    error_message = "배포 별칭이 apex · www(입력 domain_names)가 아니다."
  }

  assert {
    condition = (
      one(aws_cloudfront_distribution.this.viewer_certificate).acm_certificate_arn == var.certificate_arn &&
      one(aws_cloudfront_distribution.this.viewer_certificate).ssl_support_method == "sni-only" &&
      one(aws_cloudfront_distribution.this.viewer_certificate).minimum_protocol_version == "TLSv1.2_2021"
    )
    error_message = "시청자 인증서가 입력 인증서 · SNI · TLSv1.2_2021 이 아니다."
  }

  assert {
    condition     = aws_cloudfront_distribution.this.web_acl_id == var.web_acl_arn
    error_message = "배포에 입력 CLOUDFRONT WAF(web_acl_arn)가 붙지 않았다."
  }

  assert {
    condition = (
      aws_cloudfront_distribution.this.enabled &&
      aws_cloudfront_distribution.this.is_ipv6_enabled &&
      aws_cloudfront_distribution.this.default_root_object == "index.html"
    )
    error_message = "배포가 켜짐 · IPv6(AAAA 별칭 전제) · 기본 객체 index.html 이 아니다."
  }
}

run "runs_apex_request_on_viewer_request" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_function.apex_request.runtime == "cloudfront-js-2.0" &&
      aws_cloudfront_function.apex_request.publish &&
      aws_cloudfront_function.apex_request.code == file("${path.module}/functions/apex-request.js")
    )
    error_message = "apex-request 함수가 functions/apex-request.js 원문 · cloudfront-js-2.0 · 게시가 아니다."
  }

  assert {
    condition = [
      for association in one(aws_cloudfront_distribution.this.default_cache_behavior).function_association :
      { event_type = association.event_type, function_arn = association.function_arn }
      ] == [{
        event_type   = "viewer-request"
        function_arn = "arn:aws:cloudfront::111122223333:function/pokeclip-prod-apex-request"
    }]
    error_message = "기본 비헤이비어의 함수 연결이 viewer-request = apex-request 하나가 아니다."
  }
}

run "caches_optimized_and_redirects_to_https" {
  command = plan

  # 관리형 CachingOptimized 의 id(AWS 문서 using-managed-cache-policies —
  # 2026-10-24 확인).

  assert {
    condition = (
      one(aws_cloudfront_distribution.this.default_cache_behavior).cache_policy_id == "658327ea-f89d-4fab-a63d-7e88639e58f6" &&
      one(aws_cloudfront_distribution.this.default_cache_behavior).viewer_protocol_policy == "redirect-to-https"
    )
    error_message = "기본 비헤이비어가 CachingOptimized · redirect-to-https 가 아니다."
  }

  assert {
    condition = (
      one(aws_cloudfront_distribution.this.default_cache_behavior).allowed_methods == toset(["GET", "HEAD"]) &&
      one(aws_cloudfront_distribution.this.default_cache_behavior).cached_methods == toset(["GET", "HEAD"])
    )
    error_message = "정적 사이트 비헤이비어의 메서드가 GET · HEAD 만이 아니다."
  }

  assert {
    condition     = length(aws_cloudfront_distribution.this.ordered_cache_behavior) == 0
    error_message = "정적 사이트 배포에 기본 밖 비헤이비어가 있다."
  }
}

run "sets_hsts_without_subdomains_preload_or_csp" {
  command = plan

  assert {
    condition = (
      one(aws_cloudfront_distribution.this.default_cache_behavior).response_headers_policy_id ==
      "00000000-0000-0000-0000-00000000hsts"
    )
    error_message = "기본 비헤이비어에 HSTS 응답 헤더 정책이 붙지 않았다."
  }

  assert {
    condition = one([
      for config in aws_cloudfront_response_headers_policy.hsts.security_headers_config : [
        for hsts in config.strict_transport_security : {
          max_age            = hsts.access_control_max_age_sec
          override           = hsts.override
          include_subdomains = hsts.include_subdomains
          preload            = hsts.preload
        }
      ]
      ]) == [{
      max_age            = 300
      override           = true
      include_subdomains = false
      preload            = false
    }]
    error_message = "HSTS 가 max-age 300(기본) · 덮어쓰기 · includeSubDomains 없음 · preload 없음이 아니다."
  }

  assert {
    condition = alltrue([
      for config in aws_cloudfront_response_headers_policy.hsts.security_headers_config :
      length(config.content_security_policy) == 0
    ])
    error_message = "응답 헤더 정책에 CSP 가 있다."
  }

  assert {
    condition = (
      length(aws_cloudfront_response_headers_policy.hsts.cors_config) == 0 &&
      length(aws_cloudfront_response_headers_policy.hsts.custom_headers_config) == 0
    )
    error_message = "HSTS 응답 헤더 정책에 CORS · 사용자 정의 헤더가 섞였다."
  }
}

run "uses_hsts_max_age_input" {
  command = plan

  variables {
    hsts_max_age = 31536000
  }

  assert {
    condition = one(flatten([
      for config in aws_cloudfront_response_headers_policy.hsts.security_headers_config : [
        for hsts in config.strict_transport_security : hsts.access_control_max_age_sec
      ]
    ])) == 31536000
    error_message = "HSTS max-age 가 입력 hsts_max_age 가 아니다."
  }
}

run "outputs_distribution_and_bucket" {
  command = plan

  assert {
    condition = (
      output.distribution_id == "E0EXAMPLEAPEX" &&
      output.distribution_arn == "arn:aws:cloudfront::111122223333:distribution/E0EXAMPLEAPEX" &&
      output.domain_name == "d0example.cloudfront.net" &&
      output.hosted_zone_id == "Z2FDTNDATAQYW2" &&
      output.bucket_name == "pokeclip-prod-apex-example"
    )
    error_message = "출력(distribution_id · distribution_arn · domain_name · hosted_zone_id · bucket_name)이 배포 · 버킷 값과 다르다."
  }
}

# 입력 검증 — 잘못 넘기면 plan 전에 멈춘다.
run "rejects_empty_domain_names" {
  command = plan

  variables {
    domain_names = []
  }

  expect_failures = [var.domain_names]
}

run "rejects_duplicate_domain_names" {
  command = plan

  variables {
    domain_names = ["pokeclip.com", "pokeclip.com"]
  }

  expect_failures = [var.domain_names]
}

# CloudFront 는 us-east-1 인증서만 쓴다 — 서울 인증서를 넘기면 멈춘다.
run "rejects_certificate_outside_us_east_1" {
  command = plan

  variables {
    certificate_arn = "arn:aws:acm:ap-northeast-2:111122223333:certificate/example-apne2"
  }

  expect_failures = [var.certificate_arn]
}

# REGIONAL ACL(platform 의 공개 ALB 용)을 넘기면 멈춘다.
run "rejects_regional_web_acl" {
  command = plan

  variables {
    web_acl_arn = "arn:aws:wafv2:ap-northeast-2:111122223333:regional/webacl/pokeclip-prod-regional/0example"
  }

  expect_failures = [var.web_acl_arn]
}

run "rejects_negative_hsts_max_age" {
  command = plan

  variables {
    hsts_max_age = -1
  }

  expect_failures = [var.hsts_max_age]
}

run "rejects_invalid_bucket_prefix" {
  command = plan

  variables {
    bucket_prefix = "Pokeclip_Apex_"
  }

  expect_failures = [var.bucket_prefix]
}
