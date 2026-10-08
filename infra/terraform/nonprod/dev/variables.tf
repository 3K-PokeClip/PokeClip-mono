# import 대상의 실제 id · 현재 값은 전부 terraform.tfvars 에 둔다(추적 안 함).
# 값은 운영 O1 전의 읽기 조사로 채운다.

variable "region" {
  description = "nonprod 계정 리전."
  type        = string
  default     = "ap-northeast-2"
}

variable "instance_id" {
  description = "dev EC2 인스턴스 id."
  type        = string

  validation {
    condition     = can(regex("^i-[0-9a-f]{8,17}$", var.instance_id))
    error_message = "instance_id 는 i-<16진수> 꼴이어야 한다."
  }
}

variable "eip_allocation_id" {
  description = "dev EIP 할당 id."
  type        = string

  validation {
    condition     = can(regex("^eipalloc-[0-9a-f]{8,17}$", var.eip_allocation_id))
    error_message = "eip_allocation_id 는 eipalloc-<16진수> 꼴이어야 한다."
  }
}

variable "security_group_id" {
  description = "dev SG id."
  type        = string

  validation {
    condition     = can(regex("^sg-[0-9a-f]{8,17}$", var.security_group_id))
    error_message = "security_group_id 는 sg-<16진수> 꼴이어야 한다."
  }
}

variable "ingress_rule_id_80" {
  description = "dev SG 인그레스 규칙(tcp 80) id."
  type        = string

  validation {
    condition     = can(regex("^sgr-[0-9a-f]{8,17}$", var.ingress_rule_id_80))
    error_message = "ingress_rule_id_80 은 sgr-<16진수> 꼴이어야 한다."
  }
}

variable "ingress_rule_id_443" {
  description = "dev SG 인그레스 규칙(tcp 443) id."
  type        = string

  validation {
    condition     = can(regex("^sgr-[0-9a-f]{8,17}$", var.ingress_rule_id_443))
    error_message = "ingress_rule_id_443 은 sgr-<16진수> 꼴이어야 한다."
  }
}

# 8082 규칙 · 이 변수 · 그 import 블록은 M12 에서 함께 지운다.
variable "ingress_rule_id_8082" {
  description = "dev SG 인그레스 규칙(tcp 8082) id."
  type        = string

  validation {
    condition     = can(regex("^sgr-[0-9a-f]{8,17}$", var.ingress_rule_id_8082))
    error_message = "ingress_rule_id_8082 는 sgr-<16진수> 꼴이어야 한다."
  }
}

variable "egress_rule_id" {
  description = "dev SG 이그레스 규칙(전체 허용) id."
  type        = string

  validation {
    condition     = can(regex("^sgr-[0-9a-f]{8,17}$", var.egress_rule_id))
    error_message = "egress_rule_id 는 sgr-<16진수> 꼴이어야 한다."
  }
}

# ami 는 ignore_changes 대상이지만 provider 가 인자를 요구한다. 현재 값을 넣는다.
variable "instance_ami" {
  description = "dev EC2 의 현재 AMI id."
  type        = string

  validation {
    condition     = can(regex("^ami-[0-9a-f]{8,17}$", var.instance_ami))
    error_message = "instance_ami 는 ami-<16진수> 꼴이어야 한다."
  }
}

# 인스턴스 유형은 바뀌면 인스턴스를 멈춘다. 현재 값 그대로 넣는다.
variable "instance_type" {
  description = "dev EC2 의 현재 인스턴스 유형."
  type        = string
}

# SG description 은 바뀌면 SG 를 새로 만든다(ForceNew). 현재 값 그대로 넣는다.
variable "security_group_description" {
  description = "dev SG 의 현재 description."
  type        = string
}

# 태그는 설정에 없으면 plan 이 지우려 한다. 현재 태그를 그대로 넣는다.
variable "instance_tags" {
  description = "dev EC2 의 현재 태그."
  type        = map(string)
}

variable "eip_tags" {
  description = "dev EIP 의 현재 태그."
  type        = map(string)
}

variable "security_group_tags" {
  description = "dev SG 의 현재 태그."
  type        = map(string)
}

# hibernation 은 바뀌면 인스턴스를 새로 만든다(ForceNew). 현재 값 그대로 넣는다.
variable "instance_hibernation" {
  description = "dev EC2 의 현재 hibernation 설정."
  type        = bool
}

# 설정에 없으면 provider 기본값 true 로 바꾸려 한다. 현재 값 그대로 넣는다.
variable "instance_source_dest_check" {
  description = "dev EC2 의 현재 source_dest_check."
  type        = bool
}

# 키 = local.dev_ingress_rules 의 키(포트). description · tags 는 설정에 없으면
# plan 이 지우려 하므로 현재 값을 그대로 넣는다(없으면 생략). IPv6 · 접두사
# 목록 · 참조 SG 규칙은 이 모양으로 표현하지 못한다 — O1 조사에서 나오면
# 선언을 바꾼다.
variable "ingress_rules" {
  description = "dev SG 인그레스 규칙별 현재 값(키 = 포트)."
  type = map(object({
    ip_protocol = string
    cidr_ipv4   = string
    description = optional(string)
    tags        = optional(map(string), {})
  }))

  validation {
    condition     = toset(keys(var.ingress_rules)) == toset(keys(local.dev_ingress_rules))
    error_message = "ingress_rules 의 키는 선언된 포트(local.dev_ingress_rules)와 같아야 한다."
  }

  validation {
    condition = alltrue([
      for rule in values(var.ingress_rules) :
      contains(["tcp", "udp"], rule.ip_protocol) && can(cidrhost(rule.cidr_ipv4, 0))
    ])
    error_message = "ingress_rules 의 ip_protocol 은 tcp · udp, cidr_ipv4 는 IPv4 CIDR 이어야 한다."
  }
}

variable "egress_rule" {
  description = "dev SG 이그레스 규칙의 현재 값."
  type = object({
    ip_protocol = string
    cidr_ipv4   = string
    description = optional(string)
    tags        = optional(map(string), {})
  })

  validation {
    condition     = can(cidrhost(var.egress_rule.cidr_ipv4, 0))
    error_message = "egress_rule.cidr_ipv4 는 IPv4 CIDR 이어야 한다."
  }
}
