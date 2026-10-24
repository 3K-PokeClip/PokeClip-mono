variable "alias" {
  description = "배포의 별칭 하나(예: media.pokeclip.com). 인증서가 이 이름을 덮어야 한다."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$", var.alias))
    error_message = "alias 는 소문자 도메인 이름(와일드카드 없음)이어야 한다."
  }
}

variable "certificate_arn" {
  description = "시청자 인증서 ARN(us-east-1 ACM — prod/certs 의 cert_arn_use1)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:acm:us-east-1:", var.certificate_arn))
    error_message = "certificate_arn 은 us-east-1 ACM 인증서 ARN 이어야 한다."
  }
}

variable "web_acl_arn" {
  description = "배포에 붙일 CLOUDFRONT 범위 WAF Web ACL ARN."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:wafv2:us-east-1:[^:]*:global/webacl/", var.web_acl_arn))
    error_message = "web_acl_arn 은 CLOUDFRONT 범위(global) WAF Web ACL ARN 이어야 한다."
  }
}

variable "public_key_id" {
  description = "서명 쿠키를 검증할 CloudFront 공개키 id(prod/secrets 의 public_key_id). 키 그룹에 이것 하나만 넣는다."
  type        = string

  validation {
    condition     = length(var.public_key_id) > 0
    error_message = "public_key_id 는 비어 있으면 안 된다."
  }
}

variable "app_origin" {
  description = "CORS 로 허용할 웹 앱 출처 하나(스킴 포함 · 끝 / 없음 · 와일드카드 금지 — 서명 쿠키를 credentials 로 싣는다)."
  type        = string
  default     = "https://app.pokeclip.com"

  validation {
    condition     = can(regex("^https://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]+)?$", var.app_origin))
    error_message = "app_origin 은 https:// 로 시작하는 출처 하나(경로 · 끝 / · 와일드카드 없음)여야 한다."
  }
}

variable "internal_alb_arn" {
  description = "VPC origin 이 겨눌 내부 ALB ARN(prod/platform 의 internal_alb_arn)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:elasticloadbalancing:[^:]+:[^:]*:loadbalancer/app/", var.internal_alb_arn))
    error_message = "internal_alb_arn 은 ALB(loadbalancer/app/) ARN 이어야 한다."
  }
}

variable "internal_alb_dns_name" {
  description = "media-alb 원본 도메인 = 내부 ALB DNS 이름(prod/platform 의 internal_alb_dns_name)."
  type        = string

  validation {
    condition     = length(var.internal_alb_dns_name) > 0
    error_message = "internal_alb_dns_name 은 비어 있으면 안 된다."
  }
}

variable "rewind_bucket_regional_domain_name" {
  description = "rewind-s3 원본 도메인 = 런타임 트랙 rewind 버킷의 리전 도메인 이름(버킷 OAC 정책은 런타임 트랙 2단이 붙인다)."
  type        = string

  validation {
    condition     = can(regex("\\.s3\\.[a-z0-9-]+\\.amazonaws\\.com$", var.rewind_bucket_regional_domain_name))
    error_message = "rewind_bucket_regional_domain_name 은 <버킷>.s3.<리전>.amazonaws.com 꼴이어야 한다."
  }
}

variable "log_destination" {
  description = "표준 로그 v2 를 받을 us-east-1 CloudWatch Logs 로그 그룹 ARN. null 이면 전달 자원을 만들지 않는다(Budgets 경보 때 1차 대응)."
  type        = string

  validation {
    condition     = var.log_destination == null || can(regex("^arn:aws[a-z-]*:logs:us-east-1:[^:]*:log-group:[A-Za-z0-9_-]+$", var.log_destination))
    error_message = "log_destination 은 null 또는 us-east-1 로그 그룹 ARN(이름은 영숫자 · - · _ — 끝 :* 없음)이어야 한다."
  }
}

variable "dvr_vod_ttl" {
  description = "/dvr/* · /vod/* 캐시 정책 TTL 초(min · default · max). DVR 업로더 확정 때 업로더가 붙이는 Cache-Control 로 확정 — 그때 default 를 다시 봄."
  type = object({
    min     = number
    default = number
    max     = number
  })
  default = {
    min     = 0
    default = 1
    max     = 86400
  }

  validation {
    condition = (
      var.dvr_vod_ttl.min >= 0 &&
      var.dvr_vod_ttl.min <= var.dvr_vod_ttl.default &&
      var.dvr_vod_ttl.default <= var.dvr_vod_ttl.max
    )
    error_message = "dvr_vod_ttl 은 0 <= min <= default <= max 여야 한다."
  }
}

variable "cors_allow_headers" {
  description = "CORS 로 허용할 요청 헤더 이름(하나 이상). 기본 [\"range\"] — 플레이어는 단순 GET 만 보내 사전 요청이 없다는 전제(G14)는 그대로다. 빈 목록을 막는 이유: provider 6.68.0 은 빈 목록이면 Quantity 를 보내지 않아 응답 헤더 정책 생성이 실패한다."
  type        = list(string)
  default     = ["range"]

  # 출처: terraform-provider-aws v6.68.0
  # internal/service/cloudfront/response_headers_policy.go 의
  # expandResponseHeadersPolicyAccessControlAllowHeaders — 빈 Set 이면 Items ·
  # Quantity 를 둘 다 비워 보내는데, API 는 Quantity 가 필수다.
  validation {
    condition     = length(var.cors_allow_headers) > 0
    error_message = "cors_allow_headers 는 비어 있으면 안 된다(provider 6.68.0 은 빈 목록이면 Quantity 를 보내지 않아 응답 헤더 정책 생성이 실패한다)."
  }

  # credentials 를 허용하는 응답에는 와일드카드가 와일드카드로 읽히지 않는다.
  validation {
    condition     = alltrue([for header in var.cors_allow_headers : can(regex("^[A-Za-z0-9-]+$", header))])
    error_message = "cors_allow_headers 는 헤더 이름(영숫자 · -)만이어야 한다(와일드카드 금지)."
  }
}
