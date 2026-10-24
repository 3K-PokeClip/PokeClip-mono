# 앞 루트 상태 위치 · WAF 속도 · 표준 로그. 이 루트의 값은 모두 기본값이 있다.

variable "region" {
  description = "prod 환경 리전(리스 계정) — 서울 별칭 provider · 앞 루트 상태 버킷의 리전."
  type        = string
  default     = "ap-northeast-2"
}

variable "state_bucket" {
  description = "앞 루트 상태를 읽을 prod 상태 버킷."
  type        = string
  default     = "pokeclip-tfstate-prod-apne2"
}

# runtime 키는 런타임 트랙 계약으로 고정이다(CONTRACT-runtime.md 「상태 위치」).
variable "state_keys" {
  description = "앞 루트 상태 키(terraform_remote_state)."
  type = object({
    network  = string
    certs    = string
    secrets  = string
    platform = string
    runtime  = string
  })
  default = {
    network  = "prod/network/terraform.tfstate"
    certs    = "prod/certs/terraform.tfstate"
    secrets  = "prod/secrets/terraform.tfstate"
    platform = "prod/platform/terraform.tfstate"
    runtime  = "prod/runtime/terraform.tfstate"
  }
}

# platform 의 공개 ALB(2000)보다 크다 — LL-HLS 시청자는 파트마다 요청하고,
# 같은 공인 IP(NAT · 통신사 CGNAT) 뒤에 여럿이 있다(kty 결정 2026-10-24).
variable "waf_rate_limit" {
  description = "CLOUDFRONT WAF — IP 하나가 5분 동안 보낼 수 있는 요청 수(넘으면 차단)."
  type        = number
  default     = 20000
}

variable "log_retention_days" {
  description = "media 배포 표준 로그 로그 그룹(us-east-1) 보존 일수 — CloudWatch Logs 보존 값 중 하나."
  type        = number
  default     = 7

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days 는 CloudWatch Logs 보존 값(1 · 3 · 5 · 7 · 14 · 30 · … · 3653) 중 하나여야 한다."
  }
}

# Budgets 경보 때의 1차 대응(설계 10절 · 36차)이 false 다 — 전달만 끊고 로그
# 그룹은 남긴다. 끈 동안은 설계 7절의 /live 302 · 401 신호를 쓸 수 없다.
variable "enable_standard_logs" {
  description = "media 배포 표준 로그(v2) 전달. false 면 전달 자원을 지운다(로그 그룹은 남김)."
  type        = bool
  default     = true
  nullable    = false
}
