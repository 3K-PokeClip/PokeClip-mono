# 앞 루트 상태 위치 · 여는 이름. 이 루트는 terraform.tfvars 에
# enabled_records 만 둔다(추적하지 않음).

variable "region" {
  description = "prod 환경 리전(리스 계정) — provider 리전. Route 53 은 전역이다."
  type        = string
  default     = "ap-northeast-2"
}

variable "state_bucket" {
  description = "앞 루트 상태를 읽을 prod 상태 버킷."
  type        = string
  default     = "pokeclip-tfstate-prod-apne2"
}

variable "state_keys" {
  description = "앞 루트 상태 키(terraform_remote_state)."
  type = object({
    platform = string
    edge     = string
  })
  default = {
    platform = "prod/platform/terraform.tfstate"
    edge     = "prod/edge/terraform.tfstate"
  }
}

# 기본값이 [] 인 것은 머지 뒤 실수 apply 로 공개 이름이 열리지 않게 하려고다.
# 허용 이름은 main.tf 의 local.record_specs 키가 정한다.
variable "enabled_records" {
  description = "여는 운영 이름(apex · www · app · api · media · ingest). 기본 [] = 레코드 0."
  type        = list(string)
  default     = []
  nullable    = false

  validation {
    condition = alltrue([
      for record in var.enabled_records : contains(keys(local.record_specs), record)
    ])
    error_message = "enabled_records 의 값은 apex · www · app · api · media · ingest 여야 한다."
  }

  validation {
    condition     = length(distinct(var.enabled_records)) == length(var.enabled_records)
    error_message = "enabled_records 에 같은 이름이 두 번 있으면 안 된다."
  }
}
