# 앞 루트 상태 위치 · 상자 구성 · 이미지 다이제스트 · 도구 판. 기본값이 없는
# 것(유형 · 디스크 · 다이제스트 · 판)은 추적하지 않는 terraform.tfvars 에
# 둔다(terraform.tfvars.example). 꼴 검증은 modules/media_host 가 한다. nginx
# 포트 · 헬스 경로는 platform 출력에서 읽는다(대상 그룹과 같은 값).

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
    secrets  = string
    registry = string
    runtime  = string
  })
  default = {
    network  = "prod/network/terraform.tfstate"
    platform = "prod/platform/terraform.tfstate"
    secrets  = "prod/secrets/terraform.tfstate"
    registry = "prod/registry/terraform.tfstate"
    runtime  = "prod/runtime/terraform.tfstate"
  }
}

variable "instances" {
  description = "상자 키 → 구성 판(설계 5-3). 새 구성은 config_version 을 올린 새 키로만 넣는다(runbooks/media-swap.md)."
  type = map(object({
    config_version = string
  }))
}

variable "ingest_active_key" {
  description = "NLB ingest 대상 그룹에 등록할 상자 키(instances 중 하나)."
  type        = string
}

variable "http_active_key" {
  description = "내부 ALB 대상 그룹 셋(live · control · playback)에 등록할 상자 키(instances 중 하나)."
  type        = string
}

variable "instance_type" {
  description = "Media 상자 인스턴스 유형 — Graviton(arm64)만(결정 146)."
  type        = string
}

variable "root_volume_size" {
  description = "루트 볼륨 크기(GiB, gp3) — 녹화 · DVR 이 여기에 쌓인다."
  type        = number
}

variable "control_upstream" {
  description = "제어면 상류 <호스트>:<포트>. 빈 값이면 nginx 가 제어면 경로를 404 로 답한다(I1-6 배포 전). 바꾸면 설정 반영 교체로 들어간다."
  type        = string
  default     = ""
}

variable "janitor_enabled" {
  description = "녹화 정리 janitor 를 띄운다(사이드카 이미지 재사용 — kty 확정 기본 켬)."
  type        = bool
  default     = true
}

variable "image_digests" {
  description = "Media 상자 이미지 다이제스트(sha256:<64자리>) — 저장소는 registry 출력 repo_urls. 응답기(ingest_auth)는 비우면 렌더하지 않는다(M15)."
  type = object({
    bundle          = string
    mediamtx        = string
    nginx           = string
    sidecar         = string
    loopback_sender = string
    ingest_auth     = optional(string, "")
  })

  validation {
    condition = alltrue([
      for name, digest in var.image_digests :
      can(regex("^sha256:[a-f0-9]{64}$", digest)) || (name == "ingest_auth" && digest == "")
    ])
    error_message = "image_digests 의 값은 sha256:<64자리 16진수> 다이제스트여야 한다(태그 금지 — ingest_auth 만 빈 값 허용)."
  }
}

variable "docker_version" {
  description = "AL2023 arm64 저장소의 docker 패키지 판."
  type        = string
}

variable "ecr_credential_helper_version" {
  description = "AL2023 arm64 저장소의 amazon-ecr-credential-helper 판-릴리스."
  type        = string
}

variable "compose_version" {
  description = "docker compose 플러그인 판(공식 릴리스 태그에서 v 를 뺀 값)."
  type        = string
}

variable "compose_sha256" {
  description = "compose 플러그인 docker-compose-linux-aarch64 바이너리의 sha256 — compose_version 과 함께 바꾼다."
  type        = string
}
