variable "domain_names" {
  description = "배포 별칭(예: [\"pokeclip.com\", \"www.pokeclip.com\"]). 인증서가 모두 덮어야 한다."
  type        = list(string)

  validation {
    condition     = length(var.domain_names) > 0 && length(distinct(var.domain_names)) == length(var.domain_names)
    error_message = "domain_names 는 중복 없는 이름 하나 이상이어야 한다."
  }
}

# CloudFront 는 us-east-1 의 ACM 인증서만 쓴다.
variable "certificate_arn" {
  description = "시청자 인증서 ARN(us-east-1 ACM — prod/certs 의 cert_arn_use1)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:acm:us-east-1:[^:]+:certificate/.+$", var.certificate_arn))
    error_message = "certificate_arn 은 us-east-1 ACM 인증서 ARN 이어야 한다."
  }
}

# CloudFront 에는 CLOUDFRONT 범위(global) ACL 만 붙는다.
variable "web_acl_arn" {
  description = "배포에 붙일 CLOUDFRONT 범위 WAF Web ACL ARN."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:wafv2:us-east-1:[^:]+:global/webacl/.+$", var.web_acl_arn))
    error_message = "web_acl_arn 은 CLOUDFRONT 범위(us-east-1 global) WAF Web ACL ARN 이어야 한다."
  }
}

variable "hsts_max_age" {
  description = "HSTS max-age(초). 기본은 짧게 시작한다 — includeSubDomains · preload 는 두지 않는다."
  type        = number
  default     = 300

  validation {
    condition     = var.hsts_max_age >= 0 && floor(var.hsts_max_age) == var.hsts_max_age
    error_message = "hsts_max_age 는 0 이상의 정수여야 한다."
  }
}

# S3 는 접두 뒤에 26자를 붙여 버킷 이름(63자 이하)을 짓는다.
variable "bucket_prefix" {
  description = "사이트 버킷 이름 접두(소문자 · 숫자 · - , 37자 이하)."
  type        = string
  default     = "pokeclip-prod-apex-"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{0,36}$", var.bucket_prefix))
    error_message = "bucket_prefix 는 소문자 · 숫자 · - 로 된 37자 이하(첫 글자는 소문자 · 숫자)여야 한다."
  }
}
