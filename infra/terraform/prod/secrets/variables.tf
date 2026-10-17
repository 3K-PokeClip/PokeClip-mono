variable "region" {
  description = "prod 환경 리전(리스 계정)."
  type        = string
  default     = "ap-northeast-2"
}

variable "public_key_pem" {
  description = "CloudFront 서명 공개키 PEM(비밀 아님). 값은 추적하지 않는 terraform.tfvars 또는 TF_VAR_public_key_pem."
  type        = string
  # 개인키를 잘못 넘겼을 때 plan 출력에 값이 찍히지 않게 한다(검증은 모듈).
  sensitive = true
}
