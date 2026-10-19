# CIDR · AZ 는 기본값이 없다 — 추적하지 않는 terraform.tfvars 에 둔다.
# cidr 는 dev 상자 VPC 와 겹치지 않게 고른다(O7a 선행 0 에서 dev VPC CIDR 을
# 읽어 확인 — 설계 2-3 「네트워크」).

variable "region" {
  description = "prod 환경 리전(리스 계정)."
  type        = string
  default     = "ap-northeast-2"
}

variable "cidr" {
  description = "prod VPC IPv4 CIDR(/16 부터 /26 까지 — 모듈이 검증)."
  type        = string
}

variable "azs" {
  description = "서브넷을 둘 AZ 두 개."
  type        = list(string)
}

variable "media_az" {
  description = "Media 상자 · NLB 를 둘 AZ(azs 중 하나)."
  type        = string
}

variable "nat_az" {
  description = "NAT 게이트웨이를 둘 AZ(azs 중 하나 — media_az 와 같게 두기를 권함)."
  type        = string
}
