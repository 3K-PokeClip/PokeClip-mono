# 앞 루트 상태 위치 · 일꾼 이미지 다이제스트 · 크기. 다이제스트는 기본값이 없어
# 추적하지 않는 terraform.tfvars 에 둔다(terraform.tfvars.example).

variable "region" {
  description = "prod 환경 리전(리스 계정)."
  type        = string
  default     = "ap-northeast-2"
}

variable "state_bucket" {
  description = "앞 루트 상태를 읽을 prod 상태 버킷."
  type        = string
  default     = "pokeclip-tfstate-prod-apne2"
}

# runtime 키는 런타임 트랙 계약으로 고정이다(CONTRACT-runtime.md 「상태 위치」).
variable "state_keys" {
  description = "앞 루트 상태 키(terraform_remote_state)."
  type = object({
    network  = string
    platform = string
    registry = string
    runtime  = string
  })
  default = {
    network  = "prod/network/terraform.tfstate"
    platform = "prod/platform/terraform.tfstate"
    registry = "prod/registry/terraform.tfstate"
    runtime  = "prod/runtime/terraform.tfstate"
  }
}

variable "image_digests" {
  description = "일꾼 이미지 다이제스트(sha256:<64자리>) — 저장소는 registry 출력 repo_urls."
  type = object({
    render = string
    upload = string
  })

  validation {
    condition = alltrue([
      for digest in values(var.image_digests) : can(regex("^sha256:[a-f0-9]{64}$", digest))
    ])
    error_message = "image_digests 의 값은 sha256:<64자리 16진수> 다이제스트여야 한다(태그 금지)."
  }
}

variable "workers" {
  description = "일꾼 크기(cpu 단위 · 메모리 MiB · 임시 디스크 GiB) — 출시 뒤 실측으로 조정(kty 2026-11-04)."
  type = map(object({
    cpu                   = number
    memory                = number
    ephemeral_storage_gib = optional(number)
  }))
  default = {
    render = { cpu = 2048, memory = 4096, ephemeral_storage_gib = 40 }
    upload = { cpu = 512, memory = 1024 }
  }

  # 키마다 런타임 계약(env · 비밀 · 작업 역할 대상)과 이미지 저장소가 따로 있다.
  validation {
    condition     = sort(keys(var.workers)) == sort(["render", "upload"])
    error_message = "workers 의 키는 render · upload 정확히 둘이어야 한다."
  }
}
