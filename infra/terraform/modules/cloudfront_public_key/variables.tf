variable "name" {
  description = "CloudFront 공개키 이름."
  type        = string
}

variable "public_key_pem" {
  description = "공개키 PEM(-----BEGIN PUBLIC KEY-----). 비밀 아님. 끝 줄바꿈은 모듈이 맞춘다."
  type        = string
}
