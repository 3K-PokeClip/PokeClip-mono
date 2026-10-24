variable "name" {
  description = "Web ACL 이름 · 지표 이름(예: pokeclip-prod-regional). 규칙 지표 이름은 <name>-<규칙>."
  type        = string

  # 지표 이름 규칙(영숫자 · - · _ · 128자)에 맞춘다 — 같은 값을 지표 이름에
  # 쓰고, 규칙 지표 이름은 여기에 규칙 이름을 덧붙인다.
  validation {
    condition     = can(regex("^[A-Za-z0-9_-]{1,64}$", var.name))
    error_message = "name 은 영숫자 · - · _ 로만 된 1 ~ 64자여야 한다."
  }
}

variable "scope" {
  description = "REGIONAL(ALB 등 서울 자원에 붙임) 또는 CLOUDFRONT(us-east-1 provider 로 만듦)."
  type        = string

  validation {
    condition     = contains(["REGIONAL", "CLOUDFRONT"], var.scope)
    error_message = "scope 는 REGIONAL 또는 CLOUDFRONT 여야 한다."
  }
}

variable "managed_rule_groups" {
  description = "붙일 AWS 관리 규칙 그룹 이름(예: AWSManagedRulesCommonRuleSet). 순서 = 평가 순서(속도 제한 다음)."
  type        = list(string)

  validation {
    condition     = length(var.managed_rule_groups) > 0 && length(distinct(var.managed_rule_groups)) == length(var.managed_rule_groups)
    error_message = "managed_rule_groups 는 비어 있지 않고 이름이 겹치지 않아야 한다."
  }
}

variable "rate_limit" {
  description = "IP 하나가 5분 동안 보낼 수 있는 요청 수. 넘으면 차단한다."
  type        = number

  # AWS WAF API RateBasedStatement.Limit 의 허용 범위.
  validation {
    condition     = floor(var.rate_limit) == var.rate_limit && var.rate_limit >= 10 && var.rate_limit <= 2000000000
    error_message = "rate_limit 은 10 부터 2,000,000,000 까지의 정수여야 한다."
  }
}

variable "protected_headers" {
  description = "웹 ACL 데이터 보호로 값을 REDACTED 로 바꿀 헤더 이름(요청 샘플 · 로그 모두). 빈 목록(기본)이면 끈다."
  type        = list(string)
  default     = []

  # AWS WAF API FieldToProtect.FieldKeys 의 제약.
  validation {
    condition     = length(var.protected_headers) <= 100 && alltrue([for header in var.protected_headers : length(header) <= 64 && can(regex("\\S", header))])
    error_message = "protected_headers 는 100개 이하이고, 이름마다 64자 이하 · 공백만으로 된 이름이 아니어야 한다."
  }
}

variable "logging" {
  description = "트래픽 로그(CloudWatch Logs 로 차단된 요청만). null(기본)이면 끈다. log_group_name 은 aws-waf-logs- 로 시작 · retention_in_days 는 보존 일수 · redacted_headers 는 로그에서 가릴 헤더 이름(소문자)."
  type = object({
    log_group_name    = string
    retention_in_days = number
    redacted_headers  = list(string)
  })
  default = null

  # WAF 는 이 접두가 없는 로그 그룹을 로그 대상으로 받지 않는다(WAF 개발자
  # 안내서 「Log group naming」).
  validation {
    condition     = var.logging == null || startswith(try(var.logging.log_group_name, ""), "aws-waf-logs-")
    error_message = "logging.log_group_name 은 aws-waf-logs- 로 시작해야 한다."
  }

  validation {
    condition     = var.logging == null || contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], try(var.logging.retention_in_days, 0))
    error_message = "logging.retention_in_days 는 CloudWatch Logs 보존 값(1 · 3 · 5 · 7 · 14 · 30 · … · 3653) 중 하나여야 한다."
  }

  # provider 의 single_header.name 은 소문자로 받는다.
  validation {
    condition     = var.logging == null || alltrue([for header in try(var.logging.redacted_headers, []) : header == lower(header)])
    error_message = "logging.redacted_headers 의 헤더 이름은 소문자여야 한다."
  }
}
