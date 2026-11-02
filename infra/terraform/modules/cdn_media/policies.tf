# 비헤이비어가 쓰는 정책(계획 M10 c5 표 · 설계 3-3).
#
# 캐시 정책은 사용자 정의만 쓴다 — 관리형 UseOriginCacheControlHeaders 계열은
# 쿠키 전부를 키에 넣어 서명 쿠키가 시청자마다 키를 쪼갠다(guard R8). 어느
# 정책도 쿠키 · 헤더를 키에 넣지 않는다. 원본에 넘길 헤더 · 쿼리는 원본 요청
# 정책이 정한다.

# 캐시 끔(관리형 CachingDisabled 와 같은 뜻) — /api/ingest · /playback · 기본.
resource "aws_cloudfront_cache_policy" "no_cache" {
  name        = "${local.name_prefix}-media-no-cache"
  comment     = "media: no caching"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 0

  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# 블로킹 플레이리스트 — 키는 LL-HLS 쿼리 셋 · 1초(원본 no-cache 를 덮어 요청
# 합치기, 설계 5-1 (c)).
resource "aws_cloudfront_cache_policy" "live_playlist" {
  name        = "${local.name_prefix}-media-live-playlist"
  comment     = "media: /live/*.m3u8 keyed by _HLS_* for 1s"
  min_ttl     = 1
  default_ttl = 1
  max_ttl     = 1

  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "whitelist"

      query_strings {
        items = ["_HLS_msn", "_HLS_part", "_HLS_skip"]
      }
    }
  }
}

# 조각 · 파트 · init — 원본 Cache-Control 을 따른다(min 0 은 Bearer 불일치 때
# 원본의 private, no-cache 를 캐시하지 않으려고).
resource "aws_cloudfront_cache_policy" "live_segment" {
  name        = "${local.name_prefix}-media-live-segment"
  comment     = "media: /live/* follows origin up to 3600s"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 3600

  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

resource "aws_cloudfront_cache_policy" "dvr_vod" {
  name        = "${local.name_prefix}-media-dvr-vod"
  comment     = "media: /dvr/* and /vod/* from the rewind bucket"
  min_ttl     = var.dvr_vod_ttl.min
  default_ttl = var.dvr_vod_ttl.default
  max_ttl     = var.dvr_vod_ttl.max

  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# 되감기 제어면 — 원본이 Cache-Control 을 주지 않으면 1초.
resource "aws_cloudfront_cache_policy" "rewind_api" {
  name        = "${local.name_prefix}-media-rewind-api"
  comment     = "media: /api/streams/*/rewind for 1s"
  min_ttl     = 0
  default_ttl = 1
  max_ttl     = 1

  parameters_in_cache_key_and_forwarded_to_origin {
    cookies_config {
      cookie_behavior = "none"
    }

    headers_config {
      header_behavior = "none"
    }

    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# /live · /api/ingest — Host 와 함수가 덮어쓴 Authorization 만 넘긴다(쿠키 ·
# 그 밖의 시청자 헤더는 넘기지 않는다 — AllViewer 금지).
resource "aws_cloudfront_origin_request_policy" "host_authorization" {
  name    = "${local.name_prefix}-media-host-authorization"
  comment = "media: forward Host and Authorization only"

  cookies_config {
    cookie_behavior = "none"
  }

  headers_config {
    header_behavior = "whitelist"

    headers {
      items = ["Host", "Authorization"]
    }
  }

  query_strings_config {
    query_string_behavior = "none"
  }
}

# 되감기 제어면 — Host 만.
resource "aws_cloudfront_origin_request_policy" "host" {
  name    = "${local.name_prefix}-media-host"
  comment = "media: forward Host only"

  cookies_config {
    cookie_behavior = "none"
  }

  headers_config {
    header_behavior = "whitelist"

    headers {
      items = ["Host"]
    }
  }

  query_strings_config {
    query_string_behavior = "none"
  }
}

# 재생 API — Host + 쿼리 전부. 서명 쿠키는 엣지에서 검증하므로 넘기지 않는다.
resource "aws_cloudfront_origin_request_policy" "playback" {
  name    = "${local.name_prefix}-media-playback"
  comment = "media: forward Host and all query strings"

  cookies_config {
    cookie_behavior = "none"
  }

  headers_config {
    header_behavior = "whitelist"

    headers {
      items = ["Host"]
    }
  }

  query_strings_config {
    query_string_behavior = "all"
  }
}

# 응답 헤더 정책(설계 3-3 표) — 출처는 정확히 app_origin, 원본 CORS 헤더를 덮는다.
# 시청 경로는 서명 쿠키를 credentials 로 싣고, 되감기 제어면은 싣지 않는다(4-5).
resource "aws_cloudfront_response_headers_policy" "media_cors" {
  name    = "${local.name_prefix}-media-cors"
  comment = "media: CORS for the app origin with credentials"

  cors_config {
    access_control_allow_credentials = true
    origin_override                  = true

    access_control_allow_origins {
      items = [var.app_origin]
    }

    access_control_allow_methods {
      items = ["GET", "HEAD", "OPTIONS"]
    }

    access_control_allow_headers {
      items = var.cors_allow_headers
    }
  }
}

resource "aws_cloudfront_response_headers_policy" "rewind_cors" {
  name    = "${local.name_prefix}-media-rewind-cors"
  comment = "media: CORS for the app origin without credentials"

  cors_config {
    access_control_allow_credentials = false
    origin_override                  = true

    access_control_allow_origins {
      items = [var.app_origin]
    }

    access_control_allow_methods {
      items = ["GET", "HEAD", "OPTIONS"]
    }

    access_control_allow_headers {
      items = var.cors_allow_headers
    }
  }
}
