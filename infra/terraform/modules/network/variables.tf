variable "name" {
  description = "Name 태그 접두(예: pokeclip-prod). 자원 Name 은 <name>-<용도>."
  type        = string
}

variable "cidr" {
  description = "VPC IPv4 CIDR(/16 부터 /25 까지 · 네트워크 주소). 네 등분해 퍼블릭 둘 · 프라이빗 둘에 준다."
  type        = string

  # 네 등분한 서브넷이 ALB 서브넷 최소 크기(/27)보다 작아지지 않게 한다. 호스트
  # 비트가 남은 값(네트워크 주소가 아님)은 받지 않는다.
  validation {
    condition = try(
      cidrnetmask(var.cidr) != "" &&
      cidrhost(var.cidr, 0) == split("/", var.cidr)[0] &&
      tonumber(split("/", var.cidr)[1]) >= 16 && tonumber(split("/", var.cidr)[1]) <= 25,
      false,
    )
    error_message = "cidr 는 /16 부터 /25 까지의 IPv4 네트워크 주소 CIDR 이어야 한다(호스트 비트 없음)."
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

variable "interface_endpoint_services" {
  description = "인터페이스 엔드포인트를 둘 AWS 서비스 접미(예: ecr.api · sqs — com.amazonaws.<리전>. 뒤). 비면 엔드포인트 · 엔드포인트 SG 를 만들지 않는다."
  type        = list(string)
  default     = []

  validation {
    condition     = length(distinct(var.interface_endpoint_services)) == length(var.interface_endpoint_services)
    error_message = "interface_endpoint_services 에 같은 서비스가 두 번 있다."
  }

  # 라벨은 소문자 · 숫자 · 하이픈이고 첫 · 끝 글자는 영숫자다. 하이픈을 받으면
  # 전체 이름(com.amazonaws.<리전>.<접미>)도 꼴에 맞으므로 머리를 따로 막는다.
  validation {
    condition = alltrue([
      for service in var.interface_endpoint_services :
      can(regex("^[a-z0-9]+(-[a-z0-9]+)*(\\.[a-z0-9]+(-[a-z0-9]+)*)*$", service)) &&
      !startswith(service, "com.amazonaws.")
    ])
    error_message = "interface_endpoint_services 는 서비스 접미(소문자 · 숫자 · 하이픈 라벨을 점으로 이음, 라벨의 첫 · 끝은 영숫자, 예: ecr.api · ecs-agent)만 받는다 — com.amazonaws. 로 시작하는 전체 서비스 이름이 아니다."
  }
}

variable "interface_endpoint_private_dns" {
  description = "인터페이스 엔드포인트의 private DNS. 되돌리기 스위치다 — false 면 엔드포인트 · SG 는 남고 VPC 의 호출이 공용 이름(NAT)으로 돌아간다."
  type        = bool
  default     = true
}
