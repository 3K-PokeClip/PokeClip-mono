variable "name" {
  description = "자원 이름 접두(예: pokeclip-prod). NLB · SG 는 <name>-nlb, 대상 그룹은 <name>-ingest."
  type        = string
}

variable "vpc_id" {
  description = "NLB SG · 대상 그룹의 VPC id."
  type        = string
}

variable "subnet_id" {
  description = "NLB 를 둘 서브넷 id 하나 — Media AZ 의 퍼블릭 서브넷(network 출력 media_public_subnet_id)."
  type        = string
}

variable "srt_port" {
  description = "SRT 수신 UDP 포트(리스너 · 대상 그룹 · SG 인그레스)."
  type        = number
  default     = 8890

  validation {
    condition     = var.srt_port >= 1 && var.srt_port <= 65535 && floor(var.srt_port) == var.srt_port
    error_message = "srt_port 는 1 부터 65535 까지의 정수여야 한다."
  }
}

variable "health_port" {
  description = "대상 그룹 헬스체크 TCP 포트(Media 헬스 포트). srt_port 와 달라야 한다."
  type        = number

  validation {
    condition     = var.health_port >= 1 && var.health_port <= 65535 && floor(var.health_port) == var.health_port
    error_message = "health_port 는 1 부터 65535 까지의 정수여야 한다."
  }

  # 헬스는 TCP 로 본다 — UDP 트래픽 포트와 같은 번호면 헬스가 무엇을 보는지
  # 흐려진다.
  validation {
    condition     = var.health_port != var.srt_port
    error_message = "health_port 는 srt_port 와 다른 포트여야 한다."
  }
}

variable "deregistration_delay" {
  description = "대상 그룹 등록 해제 지연(초)."
  type        = number
  default     = 30

  validation {
    condition     = var.deregistration_delay >= 0 && var.deregistration_delay <= 3600 && floor(var.deregistration_delay) == var.deregistration_delay
    error_message = "deregistration_delay 는 0 부터 3600 까지의 정수(초)여야 한다."
  }
}

variable "ingest_allowed_cidrs" {
  description = "SRT(UDP srt_port) 인그레스를 허용할 IPv4 CIDR 목록 — 지금은 팀 IP 만(설계 5-5). 0.0.0.0/0 으로 넓히는 것은 별도 승인 지점."
  type        = list(string)

  validation {
    condition     = length(var.ingest_allowed_cidrs) > 0
    error_message = "ingest_allowed_cidrs 는 비어 있으면 안 된다(빈 목록이면 송출이 모두 막힌다)."
  }

  validation {
    condition = alltrue([
      for cidr in var.ingest_allowed_cidrs :
      can(cidrnetmask(cidr)) && strcontains(cidr, "/")
    ])
    error_message = "ingest_allowed_cidrs 의 원소는 IPv4 CIDR(접두 길이 포함)이어야 한다."
  }
}
