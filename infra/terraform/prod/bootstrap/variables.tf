variable "region" {
  description = "prod 환경 리전(리스 계정)."
  type        = string
  default     = "ap-northeast-2"
}

variable "state_bucket_name" {
  description = "prod 상태 버킷 이름(전역 유일). 고정 이름(결정 44 ③)이라 기본값을 쓴다."
  type        = string
  default     = "pokeclip-tfstate-prod-apne2"
}
