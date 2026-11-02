# CloudFront 엣지(설계 3-1 의 ⑩ · 계획 M10 c8) — CLOUDFRONT WAF 하나를 apex ·
# media 두 배포가 같이 쓴다. apex 배포 = pokeclip.com · www(cdn_static_site),
# media 배포 = media.pokeclip.com(cdn_media — KVS 그릇 · 함수 · 키 그룹 ·
# VPC origin 포함). media 배포의 표준 로그는 이 루트의 us-east-1 로그 그룹으로
# 받는다.
#
# 이 루트에 없는 것: apex 업로드 역할 · 정책(설계 8절 후속 승인 지점 — 40차),
# rewind 버킷 정책(런타임 트랙 2단 — 출력 media_distribution_arn 을 씀) · 버킷
# CORS 규칙(런타임 1단 — CONTRACT-runtime.md 「rewind 버킷 CORS」).

locals {
  # 관리 그룹은 platform 공개 ALB WAF 와 같은 둘이다(prod/platform 의
  # waf_managed_rule_groups 기본값 — 결정 155). 속도만 변수로 다르게 둔다.
  cloudfront_waf = {
    name  = "pokeclip-prod-cloudfront"
    scope = "CLOUDFRONT"
    managed_rule_groups = [
      "AWSManagedRulesKnownBadInputsRuleSet",
      "AWSManagedRulesAmazonIpReputationList",
    ]
    rate_limit = var.waf_rate_limit
  }

  # WAF 차단 로그 보존은 platform(공개 WAF · ECS 로그)과 같은 값이다.
  waf_log_retention_days = 30

  # 표준 로그를 끄면 모듈이 전달 자원을 만들지 않는다(로그 그룹은 남김).
  #
  # ARN 은 자원의 arn 속성이 아니라 plan 때 아는 값으로 짓는다 — 로그 그룹을
  # 처음 만드는 plan 에서 arn 은 미정이고, 모듈의 전달 자원 count(null
  # 여부)가 미정 값에 기대면 plan 이 멈춘다(Invalid count argument). 이름은
  # 자원 속성으로 읽어 로그 그룹 → 전달 순서를 지킨다. provider 는 arn 끝의
  # :* 를 떼므로 같은 꼴이다.
  media_log_group_arn = format(
    "arn:%s:logs:%s:%s:log-group:%s",
    data.aws_partition.current.partition,
    data.aws_region.current.region,
    data.aws_caller_identity.current.account_id,
    aws_cloudwatch_log_group.media_access.name,
  )
  media_log_destination = var.enable_standard_logs ? local.media_log_group_arn : null
}

data "aws_partition" "current" {}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

module "waf" {
  source = "../../modules/waf_acl"

  name                = local.cloudfront_waf.name
  scope               = local.cloudfront_waf.scope
  managed_rule_groups = local.cloudfront_waf.managed_rule_groups
  rate_limit          = local.cloudfront_waf.rate_limit

  # Authorization · media 시청자의 서명 쿠키 · 내부 토큰 헤더 둘(auth
  # /internal/** 공용 X-Internal-Token · 계약4 resolve 전용
  # X-Stream-Key-Resolve-Token — 정상 경로로는 엣지에 오지 않지만 잘못 실려 와도
  # 남지 않게 미리 둔다)은 웹 ACL 데이터 보호로 요청 샘플 · 로그 모두에서
  # 가린다(POK-459 · POK-479). 질의 인자는 가리지 않는다 — 자격 증명이 실리는
  # ?code=(OAuth 콜백) · ?token=(프로필 사진)은 app 호스트라 엣지가 아니라 공개
  # ALB 로 간다(prod/records · platform 의 protected_query_args).
  #
  # referer 는 platform 만 가린다(kty 2026-10-25). ?code= 가 든 전체 주소가
  # Referer 에 실릴 수 있는 것은 콜백 화면이 같은 출처(app)로 보내는 요청뿐이다
  # (web 이 콜백 화면에 no-referrer 를 명시해 정상 경로로는 그마저 없다 —
  # POK-482). app 은 CloudFront 를 지나지 않고(prod/records — app 은 공개
  # ALB), app 화면이 media · apex 로 보내는 요청은 다른 출처라 web 이 명시한
  # 전역 정책 strict-origin-when-cross-origin(브라우저 기본값과 같다)이
  # 출처(https://app.<도메인>/)만 싣는다.
  protected_headers = [
    "authorization",
    "cookie",
    "x-internal-token",
    "x-stream-key-resolve-token",
  ]

  # 차단된 요청만 남긴다(결정 161). redacted_headers 는 데이터 보호와 겹쳐 두는
  # 로그 가림이다 — 데이터 보호를 끄거나 줄여도 로그의 authorization · cookie
  # 는 가려진다. 다만 AWS 가 보장하는 범위는 그 헤더를 단일 헤더(SingleHeader)로
  # 보는 규칙까지이고, 헤더 전체(Headers)를 보는 규칙의 일치 세부 · 요청 샘플은
  # 범위 밖이다(AWS WAF API 「LoggingConfiguration」 RedactedFields). 내부 토큰
  # 헤더는 데이터 보호로만 가린다 — 정상 경로로 들어오지 않는 선제 항목이라
  # 로그 가림까지 겹치지 않는다(kty 2026-10-25).
  logging = {
    log_group_name    = "aws-waf-logs-pokeclip-prod-cloudfront"
    retention_in_days = local.waf_log_retention_days
    redacted_headers  = ["authorization", "cookie"]
  }
}

module "apex" {
  source = "../../modules/cdn_static_site"

  domain_names    = ["pokeclip.com", "www.pokeclip.com"]
  certificate_arn = local.certs.cert_arn_use1
  web_acl_arn     = module.waf.web_acl_arn
}

module "media" {
  source = "../../modules/cdn_media"

  alias                              = "media.pokeclip.com"
  certificate_arn                    = local.certs.cert_arn_use1
  web_acl_arn                        = module.waf.web_acl_arn
  public_key_id                      = local.secrets.public_key_id
  internal_alb_arn                   = local.platform.internal_alb_arn
  internal_alb_dns_name              = local.platform.internal_alb_dns_name
  rewind_bucket_regional_domain_name = local.runtime.rewind_bucket_regional_domain_name
  log_destination                    = local.media_log_destination
}

# 표준 로그 v2 대상(us-east-1). 이름은 cdn_media 의 log_destination 검증에
# 맞춰 영숫자 · - · _ 만 쓴다.
resource "aws_cloudwatch_log_group" "media_access" {
  name              = "pokeclip-prod-media-access"
  retention_in_days = var.log_retention_days
}
