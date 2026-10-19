variable "domain" {
  description = "인증서 주 도메인(예: pokeclip.com)."
  type        = string
}

variable "sans" {
  description = "주체 대체 이름(예: [\"*.pokeclip.com\"])."
  type        = list(string)
  default     = []
}

variable "zone_id" {
  description = "검증 레코드를 둘 공개 존 id(호출 루트가 이름 조회로 얻음)."
  type        = string
}

variable "create_validation_records" {
  description = "검증 레코드를 이 모듈이 만든다. 같은 이름의 인증서를 여러 리전에 둘 때는 끄고 호출 루트가 한 벌만 만든다."
  type        = bool
  default     = true
}
