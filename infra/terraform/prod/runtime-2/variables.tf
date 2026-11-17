# 앞 루트 상태 위치. 이 루트의 값은 모두 기본값이 있다.

variable "region" {
  description = "prod 환경 리전(리스 계정) — rewind 버킷 · 앞 루트 상태 버킷의 리전."
  type        = string
  default     = "ap-northeast-2"
}

variable "state_bucket" {
  description = "앞 루트 상태를 읽을 prod 상태 버킷."
  type        = string
  default     = "pokeclip-tfstate-prod-apne2"
}

# runtime 키는 런타임 트랙 계약으로 고정이다(CONTRACT-runtime.md 「상태 위치」).
# edge 키는 prod/edge 의 backend.hcl.example key 와 같다.
variable "state_keys" {
  description = "앞 루트 상태 키(terraform_remote_state)."
  type = object({
    runtime = string
    edge    = string
  })
  default = {
    runtime = "prod/runtime/terraform.tfstate"
    edge    = "prod/edge/terraform.tfstate"
  }
}
