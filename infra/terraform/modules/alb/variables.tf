variable "name" {
  description = "ALB 이름(예: pokeclip-prod-internal). SG 는 <name>-alb, 대상 그룹은 <name>-<키>."
  type        = string

  # ALB 이름 규칙: 32자 이하 · 영숫자와 - · 처음과 끝은 영숫자 · internal- 로
  # 시작하지 않음.
  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$", var.name)) && !startswith(var.name, "internal-")
    error_message = "name 은 32자 이하의 소문자 · 숫자 · - 이고 처음과 끝이 영숫자이며 internal- 로 시작하지 않아야 한다."
  }
}

variable "internal" {
  description = "true 면 내부 ALB, false 면 인터넷 대면 ALB."
  type        = bool
}

variable "vpc_id" {
  description = "SG · 대상 그룹을 둘 VPC id."
  type        = string
}

variable "subnet_ids" {
  description = "ALB 를 둘 서브넷 id 두 개(서로 다른 AZ)."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) == 2 && length(distinct(var.subnet_ids)) == 2
    error_message = "subnet_ids 는 서로 다른 서브넷 두 개여야 한다."
  }
}

variable "certificate_arn" {
  description = "443 리스너의 ACM 인증서 ARN(ALB 와 같은 리전)."
  type        = string
}

variable "idle_timeout" {
  description = "연결 유휴 제한 시간(초). 대상의 keep-alive 보다 짧게 둔다."
  type        = number
  default     = 60

  validation {
    condition     = floor(var.idle_timeout) == var.idle_timeout && var.idle_timeout >= 1 && var.idle_timeout <= 4000
    error_message = "idle_timeout 은 1 부터 4000 까지의 정수여야 한다."
  }
}

variable "target_groups" {
  description = <<-EOT
    키 → 대상 그룹. 이름은 <name>-<키>. health_check 의 port("traffic-port" 또는
    포트 번호) · path 는 받은 그대로 쓴다. 대상 등록은 호출하는 루트(ECS 서비스 ·
    media)가 한다.
  EOT
  type = map(object({
    port        = number
    protocol    = string
    target_type = string
    health_check = object({
      port    = string
      path    = string
      matcher = optional(string, "200")
    })
  }))

  validation {
    condition = alltrue([
      for key, group in var.target_groups :
      can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", key)) && length("${var.name}-${key}") <= 32
    ])
    error_message = "target_groups 키는 소문자 · 숫자 · - 이고, 대상 그룹 이름 <name>-<키> 는 32자 이하여야 한다."
  }

  validation {
    condition = alltrue([
      for group in var.target_groups :
      contains(["HTTP", "HTTPS"], group.protocol) && contains(["ip", "instance"], group.target_type)
    ])
    error_message = "target_groups 의 protocol 은 HTTP · HTTPS, target_type 은 ip · instance 여야 한다."
  }
}

variable "max_path_values_per_rule" {
  description = "리스너 규칙 하나에 넣을 경로 값 수의 상한. 넘는 경로는 다음 우선순위의 규칙으로 나눈다."
  type        = number
  default     = 2

  # 조건 하나의 비교 문자열은 3개까지다(ALB 할당량 — 계획 P1).
  validation {
    condition     = floor(var.max_path_values_per_rule) == var.max_path_values_per_rule && var.max_path_values_per_rule >= 1 && var.max_path_values_per_rule <= 3
    error_message = "max_path_values_per_rule 은 1 부터 3 까지의 정수여야 한다."
  }
}

variable "listener_rules" {
  description = <<-EOT
    키 → 443 리스너 규칙. 경로 값 = path_prefixes 마다 「/x」 · 「/x/*」 두 개 +
    path_patterns 그대로. 경로 값을 max_path_values_per_rule 개씩 나눠 규칙
    <키>/<i> 를 만들고, 우선순위는 priority + i 다(호출자는 겹치지 않게 간격을
    둔다). path_rewrite 는 그 규칙의 URL 경로 바꿈(transform url-rewrite)이다.
  EOT
  type = map(object({
    priority      = number
    target_group  = string
    host_headers  = optional(list(string), [])
    path_prefixes = optional(list(string), [])
    path_patterns = optional(list(string), [])
    path_rewrite = optional(object({
      regex   = string
      replace = string
    }))
  }))

  validation {
    condition = alltrue([
      for rule in var.listener_rules : contains(keys(var.target_groups), rule.target_group)
    ])
    error_message = "listener_rules 의 target_group 은 target_groups 의 키여야 한다."
  }

  validation {
    condition = alltrue([
      for rule in var.listener_rules :
      length(rule.host_headers) + length(rule.path_prefixes) + length(rule.path_patterns) > 0
    ])
    error_message = "listener_rules 마다 host_headers · path_prefixes · path_patterns 중 하나는 있어야 한다."
  }

  # 규칙당 조건 값 합 5 이하 · 조건 하나의 값 3 이하(ALB 할당량 — 계획 P1).
  validation {
    condition = alltrue([
      for rule in var.listener_rules :
      length(rule.host_headers) <= 3 &&
      length(rule.host_headers) + min(var.max_path_values_per_rule, 2 * length(rule.path_prefixes) + length(rule.path_patterns)) <= 5
    ])
    error_message = "listener_rules 의 host_headers 는 3개 이하이고, 규칙 하나의 host 값 + 경로 값은 5개 이하여야 한다."
  }
}
