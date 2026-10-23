# 앞 루트 상태 위치 · 이름 · 값. 기본값이 없는 것(다이제스트 · Media 포트 ·
# 팀 IP 등)은 추적하지 않는 terraform.tfvars 에 둔다.

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
    certs    = string
    secrets  = string
    registry = string
    runtime  = string
  })
  default = {
    network  = "prod/network/terraform.tfstate"
    certs    = "prod/certs/terraform.tfstate"
    secrets  = "prod/secrets/terraform.tfstate"
    registry = "prod/registry/terraform.tfstate"
    runtime  = "prod/runtime/terraform.tfstate"
  }
}

# 대상 그룹 이름 <ALB 이름>-<키> 가 32자 안에 들게 짧게 둔다(clip-sse ·
# playback 이 가장 길다).
variable "public_alb_name" {
  description = "공개 ALB 이름(대상 그룹은 <이름>-<키>)."
  type        = string
  default     = "pokeclip-prod-public"
}

variable "internal_alb_name" {
  description = "내부 ALB 이름(대상 그룹은 <이름>-<키>)."
  type        = string
  default     = "pokeclip-prod-internal"
}

variable "app_host" {
  description = "Next 가 받는 공개 호스트."
  type        = string
  default     = "app.pokeclip.com"
}

variable "api_host" {
  description = "auth · clip 이 받는 공개 호스트(OBS 플러그인 등 브라우저 밖 클라이언트)."
  type        = string
  default     = "api.pokeclip.com"
}

# CommonRuleSet 은 넣지 않는다 — 본문 8KB 제한 규칙(SizeRestrictions_BODY)이
# 프로필 사진 업로드(최대 2MB)를 막는다.
variable "waf_managed_rule_groups" {
  description = "공개 ALB REGIONAL WAF 에 붙일 AWS 관리 규칙 그룹(순서 = 평가 순서)."
  type        = list(string)
  default     = ["AWSManagedRulesKnownBadInputsRuleSet", "AWSManagedRulesAmazonIpReputationList"]
}

variable "waf_rate_limit" {
  description = "IP 하나가 5분 동안 보낼 수 있는 요청 수(넘으면 차단)."
  type        = number
  default     = 2000
}

variable "services" {
  description = "ECS 서비스(next · auth · clip)의 포트 · 헬스 경로 · 크기 · 기동 유예(초)."
  type = map(object({
    port               = number
    health_check_path  = string
    cpu                = number
    memory             = number
    health_check_grace = number
  }))
  default = {
    next = { port = 3000, health_check_path = "/api/ping", cpu = 512, memory = 1024, health_check_grace = 60 }
    # Flyway 마이그레이션 · 스키마 검증이 기동 때 돌아 유예를 길게 둔다.
    auth = { port = 8082, health_check_path = "/actuator/health", cpu = 1024, memory = 2048, health_check_grace = 180 }
    clip = { port = 8081, health_check_path = "/actuator/health", cpu = 1024, memory = 2048, health_check_grace = 180 }
  }

  validation {
    condition     = toset(keys(var.services)) == toset(["next", "auth", "clip"])
    error_message = "services 의 키는 next · auth · clip 이어야 한다."
  }
}

variable "image_digests" {
  description = "서비스 이미지 다이제스트(sha256:<64자리>) — 저장소는 registry 출력 repo_urls."
  type = object({
    next = string
    auth = string
    clip = string
  })

  validation {
    condition = alltrue([
      for digest in values(var.image_digests) : can(regex("^sha256:[a-f0-9]{64}$", digest))
    ])
    error_message = "image_digests 의 값은 sha256:<64자리 16진수> 다이제스트여야 한다(태그 금지)."
  }
}

# Media 상자 nginx 포트의 정본 — 대상 그룹 포트 · 헬스가 이 값에서 나오고,
# 출력 media_proxy_ports 로 media 루트가 같은 값으로 상자를 띄운다.
variable "proxy_ports" {
  description = "Media 상자 nginx 듣기 포트 넷(live · playback · control · health). 1024 이상 · 서로 다름 · 응답기(8080 · 8081)와 MediaMTX 루프백(8888 · 9996 · 9997) 밖."
  type = object({
    live     = number
    playback = number
    control  = number
    health   = number
  })

  validation {
    condition = alltrue([
      for port in values(var.proxy_ports) :
      port >= 1024 && port <= 65535 && floor(port) == port && !contains([8080, 8081, 8888, 9996, 9997], port)
    ])
    error_message = "proxy_ports 는 1024 부터 65535 까지의 정수이고 8080 · 8081 · 8888 · 9996 · 9997 이 아니어야 한다."
  }

  validation {
    condition     = length(distinct(values(var.proxy_ports))) == 4
    error_message = "proxy_ports 넷은 서로 달라야 한다."
  }
}

variable "media_health_path" {
  description = "Media nginx 헬스 경로(/ 로 시작) — live · control · playback 대상 그룹 헬스체크 경로."
  type        = string

  validation {
    condition     = can(regex("^/[A-Za-z0-9._/-]*$", var.media_health_path))
    error_message = "media_health_path 는 / 로 시작하는 경로여야 한다."
  }
}

variable "ingest_allowed_cidrs" {
  description = "SRT(NLB UDP 8890) 인그레스를 허용할 IPv4 CIDR — 지금은 팀 IP 만(설계 4-6)."
  type        = list(string)

  # media_ingest 모듈과 같은 규칙을 루트에서 먼저 본다.
  validation {
    condition = var.ingest_public_approved || alltrue([
      for cidr in var.ingest_allowed_cidrs : try(tonumber(split("/", cidr)[1]) >= 16, false)
    ])
    error_message = "ingest_allowed_cidrs 에 /16 보다 넓은 CIDR 이 있다 — ingest_public_approved = true(S1 게이트 · 응답기 반영 뒤 8a 단계)일 때만 받는다."
  }
}

variable "ingest_public_approved" {
  description = "SRT 인그레스를 /16 보다 넓은 출처(0.0.0.0/0 등)로 여는 공개 승인. S1 게이트 · 응답기(M15) 반영 뒤 8a 단계에서만 true."
  type        = bool
  default     = false
}

variable "media_base_url" {
  description = "clip 서명 쿠키 정책의 영상 CDN 주소(끝 / 없음 — MEDIA_BASE_URL)."
  type        = string
  default     = "https://media.pokeclip.com"
}

variable "playback_cookie_domain" {
  description = "clip 서명 쿠키 Domain(PLAYBACK_COOKIE_DOMAIN)."
  type        = string
  default     = ".pokeclip.com"
}

# 브라우저는 api 를 부르지 않지만(같은 오리진 — ADR-089 ③) auth · clip 은 허용
# 출처가 비면 부팅하지 않는다(CorsProperties @NotEmpty).
variable "cors_allowed_origins" {
  description = "auth · clip CORS_ALLOWED_ORIGINS(쉼표 목록 · 와일드카드 금지)."
  type        = string
  default     = "https://app.pokeclip.com"

  validation {
    condition     = var.cors_allowed_origins != "" && !strcontains(var.cors_allowed_origins, "*")
    error_message = "cors_allowed_origins 는 비어 있지 않고 와일드카드가 없어야 한다."
  }
}

variable "app_db_auth" {
  description = "auth · clip 의 DB 접속 방식. iam = RDS IAM DB 인증(작업 역할 rds-db:connect), password = 비밀번호(런타임 비밀)."
  type        = string
  default     = "iam"

  validation {
    condition     = contains(["iam", "password"], var.app_db_auth)
    error_message = "app_db_auth 는 iam 또는 password 여야 한다."
  }
}

variable "postgres_sslrootcert" {
  description = "auth · clip 이미지 안 RDS CA 번들 경로(POSTGRES_SSLROOTCERT). app_db_auth = iam 이면 필수."
  type        = string
  default     = null

  validation {
    condition     = var.app_db_auth != "iam" || can(regex("^/[^[:space:]]+$", var.postgres_sslrootcert))
    error_message = "app_db_auth = iam 이면 postgres_sslrootcert 는 / 로 시작하는 이미지 안 경로여야 한다."
  }
}

variable "auth_internal_proxies" {
  description = "auth 톰캣이 X-Forwarded-* 를 믿을 소켓 대역(정규식 — server.tomcat.remoteip.internal-proxies). 빈 값이면 env 를 넣지 않아 톰캣 기본 대역(사설망 · 루프백)을 쓴다."
  type        = string
  default     = ""
}

# auth 의 STREAM_KEY_SECRET_REF_PREFIX 와 작업 역할 정책 Resource 를 이 값
# 하나로 만든다 — 둘이 어긋나면 스트림 키 발급이 권한 거부로 실패한다.
variable "stream_key_secret_prefix" {
  description = "auth 가 만드는 스트림 키 비밀 이름의 접두(/ 로 끝남)."
  type        = string
  default     = "pokeclip/prod/stream-key/"

  validation {
    condition     = can(regex("^pokeclip/prod/([A-Za-z0-9_+=.@-]+/)+$", var.stream_key_secret_prefix))
    error_message = "stream_key_secret_prefix 는 pokeclip/prod/ 로 시작하고 / 로 끝나는 Secrets Manager 이름 접두여야 한다(와일드카드 금지)."
  }
}
