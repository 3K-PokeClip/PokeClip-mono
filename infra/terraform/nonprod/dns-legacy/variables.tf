# import 대상의 실제 id · 현재 값은 전부 terraform.tfvars 에 둔다(추적 안 함).
# 값은 운영 O1 전의 읽기 조사(옛 존 레코드 목록 · NS TTL)로 채운다.

variable "region" {
  description = "nonprod 계정 리전(Route 53 은 전역이지만 provider 가 요구)."
  type        = string
  default     = "ap-northeast-2"
}

variable "zone_id" {
  description = "옛 공개 존의 hosted zone id."
  type        = string

  validation {
    condition     = can(regex("^Z[A-Z0-9]+$", var.zone_id))
    error_message = "zone_id 는 Z<영대문자 · 숫자> 꼴이어야 한다."
  }
}

variable "zone_name" {
  description = "옛 공개 존 이름."
  type        = string
  default     = "pokeclip.com"
}

# comment 는 설정에 없으면 provider 기본값(Managed by Terraform)으로 바꾸려 한다.
variable "zone_comment" {
  description = "옛 존의 현재 comment."
  type        = string
}

variable "zone_tags" {
  description = "옛 존의 현재 태그."
  type        = map(string)
}

# 존 이전(운영 O6) 때 이 옛 존은 900 으로 **내리기만** 한다(설계 9-2). 이전이
# 끝나면 옛 존은 지운다 — TTL 을 다시 올리는 대상은 새 존(prod/dns)이다.
# 평소엔 현재 값.
variable "ns_ttl" {
  description = "옛 존 apex NS 레코드 TTL(초). 현재 값을 넣는다."
  type        = number

  validation {
    condition     = var.ns_ttl > 0
    error_message = "ns_ttl 은 양수여야 한다."
  }
}

# 존이 스스로 갖는 apex NS · SOA 는 여기 넣지 않는다(NS 는 ns_ttl 로 따로,
# SOA 는 관리하지 않는다).
# 단순 레코드(name · type · ttl · records)만 표현한다. 별칭(alias) · 가중치 ·
# 지연 · 장애 조치(set_identifier) · 헬스 체크가 붙은 레코드는 이 모양으로
# import 할 수 없다 — O1 전 읽기 조사에서 나오면 선언을 먼저 고친다.
variable "records" {
  description = "옛 존의 나머지 레코드(키 = 임의 이름). 단순 레코드만(별칭 없음)."
  type = map(object({
    name    = string
    type    = string
    ttl     = number
    records = list(string)
  }))

  validation {
    condition = alltrue([
      for record in values(var.records) :
      record.type != "SOA" && !(record.type == "NS" && trimsuffix(record.name, ".") == var.zone_name)
    ])
    error_message = "records 에 apex NS · SOA 는 넣지 않는다(apex NS 는 ns_ttl 이 관리)."
  }
}
