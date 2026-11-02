# 루트 공통 변수만 둔다. 티켓 변수는 각 티켓 파일 머리에 둔다.

variable "region" {
  description = "prod 환경 리전(리스 계정)."
  type        = string
  default     = "ap-northeast-2"
}

# network 원격 상태 위치는 기본값으로 고정한다(CONTRACT-runtime.md 「상태 위치」).
variable "network_state_bucket" {
  description = "prod/network 상태가 든 prod 상태 버킷."
  type        = string
  default     = "pokeclip-tfstate-prod-apne2"
}

variable "network_state_key" {
  description = "prod/network 상태 키."
  type        = string
  default     = "prod/network/terraform.tfstate"
}
