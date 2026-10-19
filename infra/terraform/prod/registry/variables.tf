variable "region" {
  description = "prod 환경 리전(리스 계정)."
  type        = string
  default     = "ap-northeast-2"
}

# janitor 저장소는 janitor 코드가 생길 때 더한다(계획 9-1 I1-4).
variable "repository_keys" {
  description = "ECR 저장소 키 집합. 저장소 이름은 pokeclip-prod/<키>."
  type        = set(string)
  default = [
    "next",
    "auth",
    "clip",
    "media-nginx",
    "media-mediamtx",
    "media-sidecar",
    "media-ingest-auth",
  ]
}

# 태그 불변 · 푸시 때 스캔은 설계에 없는 것이라 변수로 둔다(기본 켬 — kty 47차
# 수용).
variable "image_tag_immutable" {
  description = "태그 불변(IMMUTABLE). 같은 태그를 다시 푸시하면 실패한다."
  type        = bool
  default     = true
}

variable "scan_on_push" {
  description = "이미지를 푸시할 때 기본 스캔을 돌린다."
  type        = bool
  default     = true
}
