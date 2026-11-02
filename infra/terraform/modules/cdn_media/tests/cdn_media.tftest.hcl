# media 배포 모듈(설계 3-3 의 cdn_media · 계획 M10 c5)을 mock provider 로
# 단언한다 — 비헤이비어 표(기본 + 일곱, 우선순위 순) · 정책(캐시 · 원본 요청 ·
# 응답 헤더) · 오리진 둘(VPC origin 내부 ALB · rewind 버킷 OAC) · 함수 둘과
# KVS · 키 그룹 · 오류 캐싱 · 표준 로그 v2 전달 · 출력.
# 정책 · 함수 · 키 그룹 · 오리진 id 는 override_resource 로 고정해 비헤이비어가
# 가리키는 값과 비교한다. 이름 · ARN · 계정 ID(111122223333)는 가짜 값이다.

mock_provider "aws" {}

override_resource {
  target          = aws_cloudfront_cache_policy.no_cache
  override_during = plan
  values          = { id = "cp-no-cache" }
}

override_resource {
  target          = aws_cloudfront_cache_policy.live_playlist
  override_during = plan
  values          = { id = "cp-live-playlist" }
}

override_resource {
  target          = aws_cloudfront_cache_policy.live_segment
  override_during = plan
  values          = { id = "cp-live-segment" }
}

override_resource {
  target          = aws_cloudfront_cache_policy.dvr_vod
  override_during = plan
  values          = { id = "cp-dvr-vod" }
}

override_resource {
  target          = aws_cloudfront_cache_policy.rewind_api
  override_during = plan
  values          = { id = "cp-rewind-api" }
}

override_resource {
  target          = aws_cloudfront_origin_request_policy.host_authorization
  override_during = plan
  values          = { id = "orp-host-authorization" }
}

override_resource {
  target          = aws_cloudfront_origin_request_policy.host
  override_during = plan
  values          = { id = "orp-host" }
}

override_resource {
  target          = aws_cloudfront_origin_request_policy.playback
  override_during = plan
  values          = { id = "orp-playback" }
}

override_resource {
  target          = aws_cloudfront_response_headers_policy.media_cors
  override_during = plan
  values          = { id = "rhp-media-cors" }
}

override_resource {
  target          = aws_cloudfront_response_headers_policy.rewind_cors
  override_during = plan
  values          = { id = "rhp-rewind-cors" }
}

override_resource {
  target          = aws_cloudfront_function.live_auth
  override_during = plan
  values          = { arn = "arn:aws:cloudfront::111122223333:function/example-live-auth" }
}

override_resource {
  target          = aws_cloudfront_function.deny_default
  override_during = plan
  values          = { arn = "arn:aws:cloudfront::111122223333:function/example-deny-default" }
}

override_resource {
  target          = aws_cloudfront_key_value_store.this
  override_during = plan
  values          = { arn = "arn:aws:cloudfront::111122223333:key-value-store/00000000-0000-0000-0000-000000000000" }
}

override_resource {
  target          = aws_cloudfront_key_group.this
  override_during = plan
  values          = { id = "kg-example" }
}

override_resource {
  target          = aws_cloudfront_vpc_origin.this
  override_during = plan
  values          = { id = "vo-example" }
}

override_resource {
  target          = aws_cloudfront_origin_access_control.rewind
  override_during = plan
  values          = { id = "oac-example" }
}

override_resource {
  target          = aws_cloudfront_distribution.this
  override_during = plan
  values = {
    id             = "EEXAMPLE"
    arn            = "arn:aws:cloudfront::111122223333:distribution/EEXAMPLE"
    domain_name    = "d111111abcdef8.cloudfront.net"
    hosted_zone_id = "Z2FDTNDATAQYW2"
  }
}

variables {
  alias                              = "media.pokeclip.com"
  certificate_arn                    = "arn:aws:acm:us-east-1:111122223333:certificate/example"
  web_acl_arn                        = "arn:aws:wafv2:us-east-1:111122223333:global/webacl/example/00000000-0000-0000-0000-000000000000"
  public_key_id                      = "K2EXAMPLE"
  internal_alb_arn                   = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/example-internal/0000000000000000"
  internal_alb_dns_name              = "internal-example-internal-1234567890.ap-northeast-2.elb.amazonaws.com"
  rewind_bucket_regional_domain_name = "example-rewind.s3.ap-northeast-2.amazonaws.com"
  log_destination                    = "arn:aws:logs:us-east-1:111122223333:log-group:example-cdn-media"
}

# 비헤이비어 표(계획 c5) — 우선순위 순서 · 원본 · 키 그룹 · 함수 · 정책.
run "orders_behaviors_with_live_playlist_before_live" {
  command = plan

  assert {
    condition = [
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior.path_pattern
      ] == [
      "/api/ingest/*",
      "/live/*.m3u8",
      "/live/*",
      "/dvr/*",
      "/vod/*",
      "/api/streams/*/rewind",
      "/playback/*",
    ]
    error_message = "비헤이비어 경로 · 순서가 계획 c5 표(/live/*.m3u8 이 /live/* 앞)와 다르다."
  }

  assert {
    condition = [
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior.target_origin_id
    ] == ["media-alb", "media-alb", "media-alb", "rewind-s3", "rewind-s3", "media-alb", "media-alb"]
    error_message = "비헤이비어 원본이 계획 c5 표(/dvr · /vod 만 rewind-s3)와 다르다."
  }

  assert {
    condition = alltrue(concat(
      [for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior.viewer_protocol_policy == "https-only"],
      [aws_cloudfront_distribution.this.default_cache_behavior[0].viewer_protocol_policy == "https-only"],
    ))
    error_message = "HTTPS 가 아닌 시청자 요청을 받는 비헤이비어가 있다."
  }
}

run "requires_signed_cookies_except_ingest_and_rewind_api" {
  command = plan

  assert {
    condition = [
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior :
      behavior.trusted_key_groups == null ? "" : join(",", behavior.trusted_key_groups)
    ] == ["", "kg-example", "kg-example", "kg-example", "kg-example", "", "kg-example"]
    error_message = "서명 쿠키(키 그룹)를 요구하는 비헤이비어가 계획 c5 표와 다르다."
  }

  assert {
    condition     = aws_cloudfront_distribution.this.default_cache_behavior[0].trusted_key_groups == tolist(["kg-example"])
    error_message = "기본 비헤이비어에 키 그룹이 없다."
  }

  assert {
    condition     = aws_cloudfront_key_group.this.items == toset(["K2EXAMPLE"])
    error_message = "키 그룹의 공개키가 입력 public_key_id 하나가 아니다."
  }
}

run "attaches_live_auth_to_live_and_deny_default_to_default" {
  command = plan

  assert {
    condition = [
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : [
        for association in behavior.function_association : "${association.event_type} ${association.function_arn}"
      ]
      ] == [
      [],
      ["viewer-request arn:aws:cloudfront::111122223333:function/example-live-auth"],
      ["viewer-request arn:aws:cloudfront::111122223333:function/example-live-auth"],
      [],
      [],
      [],
      [],
    ]
    error_message = "live-auth 가 /live 비헤이비어 둘에만 viewer-request 로 붙지 않았다."
  }

  assert {
    condition = [
      for association in aws_cloudfront_distribution.this.default_cache_behavior[0].function_association :
      "${association.event_type} ${association.function_arn}"
    ] == ["viewer-request arn:aws:cloudfront::111122223333:function/example-deny-default"]
    error_message = "기본 비헤이비어에 deny-default 가 viewer-request 로 붙지 않았다."
  }
}

run "uses_policy_per_behavior_table" {
  command = plan

  assert {
    condition = [
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior.cache_policy_id
    ] == ["cp-no-cache", "cp-live-playlist", "cp-live-segment", "cp-dvr-vod", "cp-dvr-vod", "cp-rewind-api", "cp-no-cache"]
    error_message = "비헤이비어 캐시 정책이 계획 c5 표와 다르다."
  }

  assert {
    condition = [
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior :
      behavior.origin_request_policy_id == null ? "-" : behavior.origin_request_policy_id
    ] == ["orp-host-authorization", "orp-host-authorization", "orp-host-authorization", "-", "-", "orp-host", "orp-playback"]
    error_message = "비헤이비어 원본 요청 정책이 계획 c5 표(/dvr · /vod 는 없음 — Authorization 미전달)와 다르다."
  }

  assert {
    condition = [
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior :
      behavior.response_headers_policy_id == null ? "-" : behavior.response_headers_policy_id
    ] == ["-", "rhp-media-cors", "rhp-media-cors", "rhp-media-cors", "rhp-media-cors", "rhp-rewind-cors", "rhp-media-cors"]
    error_message = "비헤이비어 응답 헤더 정책이 계획 c5 표(/api/ingest 는 없음)와 다르다."
  }

  assert {
    condition = (
      aws_cloudfront_distribution.this.default_cache_behavior[0].cache_policy_id == "cp-no-cache" &&
      aws_cloudfront_distribution.this.default_cache_behavior[0].origin_request_policy_id == null &&
      aws_cloudfront_distribution.this.default_cache_behavior[0].response_headers_policy_id == null
    )
    error_message = "기본 비헤이비어가 캐시 끔 · 원본 요청 정책 없음 · 응답 헤더 정책 없음이 아니다."
  }
}

run "allows_post_only_on_ingest" {
  command = plan

  assert {
    condition = [
      for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior.allowed_methods
      ] == [
      toset(["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]),
      toset(["GET", "HEAD", "OPTIONS"]),
      toset(["GET", "HEAD", "OPTIONS"]),
      toset(["GET", "HEAD", "OPTIONS"]),
      toset(["GET", "HEAD", "OPTIONS"]),
      toset(["GET", "HEAD", "OPTIONS"]),
      toset(["GET", "HEAD", "OPTIONS"]),
    ]
    error_message = "허용 메서드가 다르다 — POST 는 /api/ingest/* 에만."
  }

  assert {
    condition = alltrue(concat(
      [for behavior in aws_cloudfront_distribution.this.ordered_cache_behavior : behavior.cached_methods == toset(["GET", "HEAD"])],
      [aws_cloudfront_distribution.this.default_cache_behavior[0].cached_methods == toset(["GET", "HEAD"])],
    ))
    error_message = "GET · HEAD 밖의 메서드 응답을 캐시하는 비헤이비어가 있다."
  }
}

# 캐시 정책(사용자 정의만 — 쿠키 · 헤더는 키에 넣지 않는다).
run "keys_live_playlist_by_blocking_reload_query_for_one_second" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_cache_policy.live_playlist.min_ttl == 1 &&
      aws_cloudfront_cache_policy.live_playlist.default_ttl == 1 &&
      aws_cloudfront_cache_policy.live_playlist.max_ttl == 1
    )
    error_message = "/live/*.m3u8 캐시 정책 TTL 이 1/1/1초가 아니다."
  }

  assert {
    condition = (
      aws_cloudfront_cache_policy.live_playlist.parameters_in_cache_key_and_forwarded_to_origin[0].query_strings_config[0].query_string_behavior == "whitelist" &&
      aws_cloudfront_cache_policy.live_playlist.parameters_in_cache_key_and_forwarded_to_origin[0].query_strings_config[0].query_strings[0].items == toset(["_HLS_msn", "_HLS_part", "_HLS_skip"])
    )
    error_message = "/live/*.m3u8 캐시 키 쿼리가 _HLS_msn · _HLS_part · _HLS_skip 이 아니다."
  }
}

run "uses_no_cookie_or_header_in_any_cache_key" {
  command = plan

  assert {
    condition = alltrue([
      for policy in [
        aws_cloudfront_cache_policy.no_cache,
        aws_cloudfront_cache_policy.live_playlist,
        aws_cloudfront_cache_policy.live_segment,
        aws_cloudfront_cache_policy.dvr_vod,
        aws_cloudfront_cache_policy.rewind_api,
      ] :
      policy.parameters_in_cache_key_and_forwarded_to_origin[0].cookies_config[0].cookie_behavior == "none" &&
      policy.parameters_in_cache_key_and_forwarded_to_origin[0].headers_config[0].header_behavior == "none"
    ])
    error_message = "쿠키 · 헤더를 캐시 키에 넣는 캐시 정책이 있다(서명 쿠키가 키를 쪼갠다)."
  }

  assert {
    condition = alltrue([
      for policy in [
        aws_cloudfront_cache_policy.no_cache,
        aws_cloudfront_cache_policy.live_segment,
        aws_cloudfront_cache_policy.dvr_vod,
        aws_cloudfront_cache_policy.rewind_api,
      ] :
      policy.parameters_in_cache_key_and_forwarded_to_origin[0].query_strings_config[0].query_string_behavior == "none"
    ])
    error_message = "/live/*.m3u8 밖의 캐시 정책이 쿼리를 캐시 키에 넣는다."
  }
}

run "sets_ttls_for_segments_rewind_api_and_disabled" {
  command = plan

  assert {
    condition = [
      aws_cloudfront_cache_policy.live_segment.min_ttl,
      aws_cloudfront_cache_policy.live_segment.default_ttl,
      aws_cloudfront_cache_policy.live_segment.max_ttl,
    ] == [0, 0, 3600]
    error_message = "/live/* 캐시 정책 TTL 이 0/0/3600초(원본 따름)가 아니다."
  }

  assert {
    condition = [
      aws_cloudfront_cache_policy.rewind_api.min_ttl,
      aws_cloudfront_cache_policy.rewind_api.default_ttl,
      aws_cloudfront_cache_policy.rewind_api.max_ttl,
    ] == [0, 1, 1]
    error_message = "/api/streams/*/rewind 캐시 정책 TTL 이 1초(0/1/1)가 아니다."
  }

  assert {
    condition = [
      aws_cloudfront_cache_policy.no_cache.min_ttl,
      aws_cloudfront_cache_policy.no_cache.default_ttl,
      aws_cloudfront_cache_policy.no_cache.max_ttl,
    ] == [0, 0, 0]
    error_message = "캐시 끔 정책의 TTL 이 0/0/0 이 아니다."
  }
}

run "uses_dvr_vod_ttl_variable" {
  command = plan

  variables {
    dvr_vod_ttl = { min = 2, default = 30, max = 600 }
  }

  assert {
    condition = [
      aws_cloudfront_cache_policy.dvr_vod.min_ttl,
      aws_cloudfront_cache_policy.dvr_vod.default_ttl,
      aws_cloudfront_cache_policy.dvr_vod.max_ttl,
    ] == [2, 30, 600]
    error_message = "/dvr · /vod 캐시 정책 TTL 이 입력 dvr_vod_ttl 이 아니다."
  }
}

run "defaults_dvr_vod_ttl_to_conservative_values" {
  command = plan

  assert {
    condition = [
      aws_cloudfront_cache_policy.dvr_vod.min_ttl,
      aws_cloudfront_cache_policy.dvr_vod.default_ttl,
      aws_cloudfront_cache_policy.dvr_vod.max_ttl,
    ] == [0, 1, 86400]
    error_message = "dvr_vod_ttl 기본값이 0/1/86400초(kty 19차)가 아니다."
  }
}

run "rejects_dvr_vod_ttl_out_of_order" {
  command = plan

  variables {
    dvr_vod_ttl = { min = 10, default = 1, max = 86400 }
  }

  expect_failures = [var.dvr_vod_ttl]
}

# 원본 요청 정책 — Host · Authorization 만(쿠키 없음 · AllViewer 아님).
run "forwards_only_host_and_authorization_to_live_and_ingest" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_origin_request_policy.host_authorization.headers_config[0].header_behavior == "whitelist" &&
      aws_cloudfront_origin_request_policy.host_authorization.headers_config[0].headers[0].items == toset(["Host", "Authorization"]) &&
      aws_cloudfront_origin_request_policy.host_authorization.cookies_config[0].cookie_behavior == "none" &&
      aws_cloudfront_origin_request_policy.host_authorization.query_strings_config[0].query_string_behavior == "none"
    )
    error_message = "/live · /api/ingest 원본 요청 정책이 Host · Authorization 만이 아니다."
  }

  assert {
    condition = (
      aws_cloudfront_origin_request_policy.host.headers_config[0].headers[0].items == toset(["Host"]) &&
      aws_cloudfront_origin_request_policy.host.cookies_config[0].cookie_behavior == "none" &&
      aws_cloudfront_origin_request_policy.host.query_strings_config[0].query_string_behavior == "none"
    )
    error_message = "제어면 원본 요청 정책이 Host 만이 아니다."
  }

  assert {
    condition = (
      aws_cloudfront_origin_request_policy.playback.headers_config[0].headers[0].items == toset(["Host"]) &&
      aws_cloudfront_origin_request_policy.playback.cookies_config[0].cookie_behavior == "none" &&
      aws_cloudfront_origin_request_policy.playback.query_strings_config[0].query_string_behavior == "all"
    )
    error_message = "/playback 원본 요청 정책이 Host + 쿼리 전부 · 쿠키 없음이 아니다."
  }
}

# 응답 헤더 정책(설계 3-3 표) — 출처는 정확히 app_origin, 와일드카드 없음.
run "sets_cors_with_credentials_for_media_and_without_for_rewind_api" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_response_headers_policy.media_cors.cors_config[0].access_control_allow_origins[0].items == toset(["https://app.pokeclip.com"]) &&
      aws_cloudfront_response_headers_policy.media_cors.cors_config[0].access_control_allow_credentials == true &&
      aws_cloudfront_response_headers_policy.media_cors.cors_config[0].origin_override == true &&
      aws_cloudfront_response_headers_policy.media_cors.cors_config[0].access_control_allow_methods[0].items == toset(["GET", "HEAD", "OPTIONS"]) &&
      aws_cloudfront_response_headers_policy.media_cors.cors_config[0].access_control_allow_headers[0].items == toset(["range"])
    )
    error_message = "media-cors 가 app 출처 · credentials 허용 · 원본 덮음 · GET/HEAD/OPTIONS · 허용 헤더 range 가 아니다."
  }

  assert {
    condition = (
      aws_cloudfront_response_headers_policy.rewind_cors.cors_config[0].access_control_allow_origins[0].items == toset(["https://app.pokeclip.com"]) &&
      aws_cloudfront_response_headers_policy.rewind_cors.cors_config[0].access_control_allow_credentials == false &&
      aws_cloudfront_response_headers_policy.rewind_cors.cors_config[0].origin_override == true &&
      aws_cloudfront_response_headers_policy.rewind_cors.cors_config[0].access_control_allow_methods[0].items == toset(["GET", "HEAD", "OPTIONS"]) &&
      aws_cloudfront_response_headers_policy.rewind_cors.cors_config[0].access_control_allow_headers[0].items == toset(["range"])
    )
    error_message = "rewind-cors 가 app 출처 · credentials 없음 · 원본 덮음 · GET/HEAD/OPTIONS · 허용 헤더 range 가 아니다."
  }
}

run "passes_cors_allow_headers_and_app_origin" {
  command = plan

  variables {
    app_origin         = "https://app.example.com"
    cors_allow_headers = ["Range"]
  }

  assert {
    condition = (
      aws_cloudfront_response_headers_policy.media_cors.cors_config[0].access_control_allow_origins[0].items == toset(["https://app.example.com"]) &&
      aws_cloudfront_response_headers_policy.media_cors.cors_config[0].access_control_allow_headers[0].items == toset(["Range"]) &&
      aws_cloudfront_response_headers_policy.rewind_cors.cors_config[0].access_control_allow_headers[0].items == toset(["Range"])
    )
    error_message = "응답 헤더 정책이 입력 app_origin · cors_allow_headers 를 쓰지 않는다."
  }
}

run "rejects_wildcard_app_origin" {
  command = plan

  variables {
    app_origin = "*"
  }

  expect_failures = [var.app_origin]
}

run "rejects_wildcard_cors_allow_header" {
  command = plan

  variables {
    cors_allow_headers = ["*"]
  }

  expect_failures = [var.cors_allow_headers]
}

# provider 6.68.0 은 빈 목록이면 Quantity 를 보내지 않아 정책 생성이 실패한다.
run "rejects_empty_cors_allow_headers" {
  command = plan

  variables {
    cors_allow_headers = []
  }

  expect_failures = [var.cors_allow_headers]
}

# 오리진 둘 — media-alb(VPC origin) · rewind-s3(OAC).
run "reaches_internal_alb_through_vpc_origin_over_https" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_vpc_origin.this.vpc_origin_endpoint_config[0].arn == var.internal_alb_arn &&
      aws_cloudfront_vpc_origin.this.vpc_origin_endpoint_config[0].origin_protocol_policy == "https-only" &&
      aws_cloudfront_vpc_origin.this.vpc_origin_endpoint_config[0].https_port == 443 &&
      aws_cloudfront_vpc_origin.this.vpc_origin_endpoint_config[0].origin_ssl_protocols[0].items == toset(["TLSv1.2"]) &&
      aws_cloudfront_vpc_origin.this.vpc_origin_endpoint_config[0].origin_ssl_protocols[0].quantity == 1
    )
    error_message = "VPC origin 이 내부 ALB · https-only 443 · TLSv1.2 가 아니다."
  }

  assert {
    condition = alltrue([
      for origin in aws_cloudfront_distribution.this.origin :
      origin.domain_name == var.internal_alb_dns_name &&
      origin.vpc_origin_config[0].vpc_origin_id == "vo-example" &&
      origin.vpc_origin_config[0].origin_read_timeout == 30 &&
      length(origin.custom_origin_config) == 0 &&
      (length(origin.origin_shield) == 0 || !try(origin.origin_shield[0].enabled, false))
      if origin.origin_id == "media-alb"
    ]) && length([for origin in aws_cloudfront_distribution.this.origin : origin if origin.origin_id == "media-alb"]) == 1
    error_message = "media-alb 원본이 내부 ALB DNS · 이 VPC origin · 응답 30초 · Shield 끔이 아니다."
  }
}

run "reaches_rewind_bucket_through_oac_with_origin_shield" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_origin_access_control.rewind.origin_access_control_origin_type == "s3" &&
      aws_cloudfront_origin_access_control.rewind.signing_behavior == "always" &&
      aws_cloudfront_origin_access_control.rewind.signing_protocol == "sigv4"
    )
    error_message = "rewind OAC 가 s3 · always · sigv4 가 아니다."
  }

  assert {
    condition = alltrue([
      for origin in aws_cloudfront_distribution.this.origin :
      origin.domain_name == var.rewind_bucket_regional_domain_name &&
      origin.origin_access_control_id == "oac-example" &&
      origin.origin_shield[0].enabled == true &&
      origin.origin_shield[0].origin_shield_region == "ap-northeast-2"
      if origin.origin_id == "rewind-s3"
    ]) && length([for origin in aws_cloudfront_distribution.this.origin : origin if origin.origin_id == "rewind-s3"]) == 1
    error_message = "rewind-s3 원본이 버킷 리전 도메인 · OAC · Shield 켬(서울)이 아니다."
  }
}

run "connects_origins_within_three_seconds_twice_without_custom_headers" {
  command = plan

  assert {
    condition     = length(aws_cloudfront_distribution.this.origin) == 2
    error_message = "원본이 media-alb · rewind-s3 둘이 아니다."
  }

  assert {
    condition = alltrue([
      for origin in aws_cloudfront_distribution.this.origin :
      origin.connection_timeout == 3 && origin.connection_attempts == 2
    ])
    error_message = "원본 연결 제한이 3초 · 2회가 아니다."
  }

  assert {
    condition = alltrue([
      for origin in aws_cloudfront_distribution.this.origin : length(origin.custom_header) == 0
    ])
    error_message = "원본 커스텀 헤더가 있다(비밀은 함수가 Authorization 으로만 싣는다)."
  }
}

# 배포 공통.
run "serves_alias_with_certificate_and_waf" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_distribution.this.enabled &&
      aws_cloudfront_distribution.this.aliases == toset(["media.pokeclip.com"]) &&
      aws_cloudfront_distribution.this.web_acl_id == var.web_acl_arn
    )
    error_message = "배포가 켜져 있지 않거나 별칭 · WAF 가 입력과 다르다."
  }

  assert {
    condition = (
      aws_cloudfront_distribution.this.viewer_certificate[0].acm_certificate_arn == var.certificate_arn &&
      aws_cloudfront_distribution.this.viewer_certificate[0].ssl_support_method == "sni-only" &&
      aws_cloudfront_distribution.this.viewer_certificate[0].minimum_protocol_version == "TLSv1.2_2021"
    )
    error_message = "시청자 인증서가 입력 인증서 · SNI · TLSv1.2_2021 이 아니다."
  }
}

run "caches_errors_for_one_second_without_custom_pages" {
  command = plan

  assert {
    condition = toset([
      for response in aws_cloudfront_distribution.this.custom_error_response : response.error_code
    ]) == toset([404, 500, 502, 503, 504])
    error_message = "오류 캐싱 대상 코드가 404 · 500 · 502 · 503 · 504 가 아니다."
  }

  assert {
    condition = alltrue([
      for response in aws_cloudfront_distribution.this.custom_error_response :
      response.error_caching_min_ttl == 1 && response.response_page_path == null && response.response_code == null
    ])
    error_message = "오류 캐싱이 1초가 아니거나 응답 내용을 바꾼다."
  }
}

# 함수 둘 · KVS — 이름 접두 · 런타임 · 게시 · KVS 연결은 live-auth 에만.
run "publishes_functions_with_kvs_only_on_live_auth" {
  command = plan

  assert {
    condition = (
      aws_cloudfront_function.live_auth.runtime == "cloudfront-js-2.0" &&
      aws_cloudfront_function.live_auth.publish == true &&
      aws_cloudfront_function.live_auth.key_value_store_associations == toset([aws_cloudfront_key_value_store.this.arn])
    )
    error_message = "live-auth 가 cloudfront-js-2.0 · 게시 · KVS 연결 하나가 아니다."
  }

  assert {
    condition = (
      aws_cloudfront_function.deny_default.runtime == "cloudfront-js-2.0" &&
      aws_cloudfront_function.deny_default.publish == true &&
      length(coalesce(aws_cloudfront_function.deny_default.key_value_store_associations, toset([]))) == 0
    )
    error_message = "deny-default 가 cloudfront-js-2.0 · 게시 · KVS 연결 없음이 아니다."
  }

  # 함수 파일을 바꿔 끼우지 않았는지 — 갈래 간 공유 고정 로그 문자열.
  assert {
    condition = (
      strcontains(aws_cloudfront_function.live_auth.code, "CDN SECRET UNAVAILABLE") &&
      strcontains(aws_cloudfront_function.deny_default.code, "DENY DEFAULT")
    )
    error_message = "함수 코드가 functions/live-auth.js · deny-default.js 가 아니다."
  }
}

run "names_resources_with_prod_prefix" {
  command = plan

  assert {
    condition = alltrue([
      for name in [
        aws_cloudfront_key_value_store.this.name,
        aws_cloudfront_function.live_auth.name,
        aws_cloudfront_function.deny_default.name,
        aws_cloudfront_key_group.this.name,
        aws_cloudfront_vpc_origin.this.vpc_origin_endpoint_config[0].name,
        aws_cloudfront_origin_access_control.rewind.name,
        aws_cloudfront_cache_policy.no_cache.name,
        aws_cloudfront_cache_policy.live_playlist.name,
        aws_cloudfront_cache_policy.live_segment.name,
        aws_cloudfront_cache_policy.dvr_vod.name,
        aws_cloudfront_cache_policy.rewind_api.name,
        aws_cloudfront_origin_request_policy.host_authorization.name,
        aws_cloudfront_origin_request_policy.host.name,
        aws_cloudfront_origin_request_policy.playback.name,
        aws_cloudfront_response_headers_policy.media_cors.name,
        aws_cloudfront_response_headers_policy.rewind_cors.name,
      ] : startswith(name, "pokeclip-prod-")
    ])
    error_message = "이름 접두가 pokeclip-prod- 가 아닌 자원이 있다."
  }
}

# 표준 로그 v2 — CloudWatch Logs JSON · 쿠키 · Authorization 성격 필드 없음.
run "delivers_standard_logs_as_json_without_cookie_fields" {
  command = plan

  assert {
    condition = (
      aws_cloudwatch_log_delivery_source.this[0].log_type == "ACCESS_LOGS" &&
      aws_cloudwatch_log_delivery_source.this[0].resource_arn == "arn:aws:cloudfront::111122223333:distribution/EEXAMPLE"
    )
    error_message = "로그 전달 원천이 이 배포의 ACCESS_LOGS 가 아니다."
  }

  assert {
    condition = (
      aws_cloudwatch_log_delivery_destination.this[0].output_format == "json" &&
      aws_cloudwatch_log_delivery_destination.this[0].delivery_destination_configuration[0].destination_resource_arn == var.log_destination
    )
    error_message = "로그 전달 대상이 입력 log_destination · JSON 이 아니다."
  }

  assert {
    condition     = aws_cloudwatch_log_delivery.this[0].delivery_source_name == aws_cloudwatch_log_delivery_source.this[0].name
    error_message = "로그 전달이 이 원천을 쓰지 않는다."
  }

  assert {
    condition = !anytrue([
      for field in aws_cloudwatch_log_delivery.this[0].record_fields :
      strcontains(lower(field), "cookie") || strcontains(lower(field), "authorization") ||
      contains(["cs-headers", "cs-header-names", "cs-headers-count", "viewer-request-log-data", "viewer-response-log-data"], field)
    ])
    error_message = "로그 필드에 쿠키 · 요청 헤더 전체 · 함수 기록 데이터 필드가 있다."
  }

  # 지표 필터(설계 7절)가 쓰는 필드.
  assert {
    condition = (
      contains(aws_cloudwatch_log_delivery.this[0].record_fields, "cs-uri-stem") &&
      contains(aws_cloudwatch_log_delivery.this[0].record_fields, "sc-status")
    )
    error_message = "로그 필드에 cs-uri-stem · sc-status 가 없다."
  }
}

run "skips_log_delivery_when_destination_is_null" {
  command = plan

  variables {
    log_destination = null
  }

  assert {
    condition = (
      length(aws_cloudwatch_log_delivery_source.this) == 0 &&
      length(aws_cloudwatch_log_delivery_destination.this) == 0 &&
      length(aws_cloudwatch_log_delivery.this) == 0
    )
    error_message = "log_destination = null 인데 로그 전달 자원이 있다."
  }
}

run "exposes_distribution_kvs_and_vpc_origin" {
  command = plan

  assert {
    condition = (
      output.distribution_id == "EEXAMPLE" &&
      output.distribution_arn == "arn:aws:cloudfront::111122223333:distribution/EEXAMPLE" &&
      output.domain_name == "d111111abcdef8.cloudfront.net" &&
      output.hosted_zone_id == "Z2FDTNDATAQYW2"
    )
    error_message = "배포 출력이 배포 자원의 값과 다르다."
  }

  assert {
    condition = (
      output.kvs_arn == "arn:aws:cloudfront::111122223333:key-value-store/00000000-0000-0000-0000-000000000000" &&
      output.vpc_origin_id == "vo-example"
    )
    error_message = "kvs_arn · vpc_origin_id 출력이 자원의 값과 다르다."
  }

  # 호출 루트가 함수 로그 그룹 이름(/aws/cloudfront/function/<이름>)을 짓는다.
  assert {
    condition = output.function_names == {
      live_auth    = "pokeclip-prod-live-auth"
      deny_default = "pokeclip-prod-deny-default"
    }
    error_message = "function_names 출력이 live-auth · deny-default 함수 이름과 다르다."
  }
}
