variable "name" {
  description = "Name 태그 접두(예: pokeclip-prod). 자원 Name 은 <name>-<용도>."
  type        = string
}

variable "cidr" {
  description = "VPC IPv4 CIDR(/16 부터 /26 까지). 네 등분해 퍼블릭 둘 · 프라이빗 둘에 준다."
  type        = string

  # 네 등분한 서브넷이 AWS 서브넷 최소 크기(/28)보다 작아지지 않게 한다.
  validation {
    condition     = try(cidrnetmask(var.cidr) != "" && tonumber(split("/", var.cidr)[1]) >= 16 && tonumber(split("/", var.cidr)[1]) <= 26, false)
    error_message = "cidr 는 /16 부터 /26 까지의 IPv4 CIDR 이어야 한다."
  }
}

variable "azs" {
  description = "서브넷을 둘 AZ 두 개(순서 = 서브넷 CIDR · 출력 목록 순서)."
  type        = list(string)

  validation {
    condition     = length(var.azs) == 2 && length(distinct(var.azs)) == 2
    error_message = "azs 는 서로 다른 AZ 두 개여야 한다."
  }
}

variable "media_az" {
  description = "Media 상자 · NLB 를 둘 AZ. azs 중 하나."
  type        = string

  validation {
    condition     = contains(var.azs, var.media_az)
    error_message = "media_az 는 azs 중 하나여야 한다."
  }
}

variable "nat_az" {
  description = "NAT 게이트웨이 하나를 둘 AZ. azs 중 하나(media_az 와 같게 두기를 권함)."
  type        = string

  validation {
    condition     = contains(var.azs, var.nat_az)
    error_message = "nat_az 는 azs 중 하나여야 한다."
  }
}

variable "enable_s3_gateway_endpoint" {
  description = "S3 Gateway 엔드포인트를 만들고 프라이빗 라우트 테이블에 붙인다."
  type        = bool
  default     = true
}
