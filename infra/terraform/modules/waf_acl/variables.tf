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
