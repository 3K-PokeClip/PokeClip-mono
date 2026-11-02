variable "name" {
  description = "CloudFront 공개키 이름."
  type        = string
}

variable "public_key_pem" {
  description = "공개키 PEM(-----BEGIN PUBLIC KEY-----). 비밀 아님. 끝 줄바꿈은 모듈이 맞춘다."
  type        = string
  # 공개키는 비밀이 아니지만, 개인키를 잘못 넘겼을 때 plan 출력 · 검증 오류에
  # 값이 찍히지 않게 민감 값으로 다룬다.
  sensitive = true

  validation {
    condition     = startswith(trimspace(var.public_key_pem), "-----BEGIN PUBLIC KEY-----") && !strcontains(var.public_key_pem, "PRIVATE KEY")
    error_message = "public_key_pem 은 -----BEGIN PUBLIC KEY----- 로 시작하는 공개키 PEM 이어야 하고, PRIVATE KEY 를 담으면 안 된다(개인키를 넘기지 않는다)."
  }
}
