variable "region" {
  description = "nonprod 계정 리전."
  type        = string
  default     = "ap-northeast-2"
}

variable "state_bucket_name" {
  description = "nonprod 상태 버킷 이름(전역 유일). 값은 terraform.tfvars."
  type        = string
}
