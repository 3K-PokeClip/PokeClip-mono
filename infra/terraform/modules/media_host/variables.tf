variable "name" {
  description = "자원 이름 접두(예: pokeclip-prod). SG · 역할 · 인스턴스 프로필은 <name>-media, ECR 저장소는 <name>/<키>."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.name))
    error_message = "name 은 소문자 · 숫자 · - 만 쓴다."
  }
}

variable "iam_path" {
  description = "역할 · 인스턴스 프로필 경로(3절 이름 규약 — prod 주체 식별 기준)."
  type        = string
  default     = "/pokeclip/prod/"

  validation {
    condition     = can(regex("^/([a-z0-9-]+/)+$", var.iam_path))
    error_message = "iam_path 는 /<토막>/…/ 꼴이어야 한다."
  }
}

variable "vpc_id" {
  description = "Media SG 의 VPC id(network 출력 vpc_id)."
  type        = string
}

variable "subnet_id" {
  description = "Media 상자 서브넷 — Media AZ 의 프라이빗 서브넷(network 출력 media_private_subnet_id)."
  type        = string
}

variable "instances" {
  description = "상자 키 → 구성 판(설계 5-3 교체 모델). 새 구성은 config_version 을 올린 새 키로만 들어간다 — 이미 떠 있는 키의 user_data · AMI 는 바꾸지 않는다."
  type = map(object({
    config_version = string
  }))

  validation {
    condition     = length(var.instances) > 0 && alltrue([for key in keys(var.instances) : can(regex("^[a-z0-9]+$", key))])
    error_message = "instances 는 하나 이상이고 키는 소문자 · 숫자만 쓴다."
  }

  validation {
    condition     = alltrue([for instance in values(var.instances) : can(regex("^[A-Za-z0-9._-]+$", instance.config_version))])
    error_message = "config_version 은 영문 · 숫자 · . _ - 만 쓴다."
  }
}

variable "ingest_active_key" {
  description = "NLB ingest 대상 그룹에 등록할 상자 키(instances 중 하나)."
  type        = string

  validation {
    condition     = contains(keys(var.instances), var.ingest_active_key)
    error_message = "ingest_active_key 는 instances 의 키여야 한다."
  }
}

variable "http_active_key" {
  description = "내부 ALB 대상 그룹 셋(live · control · playback)에 등록할 상자 키(instances 중 하나)."
  type        = string

  validation {
    condition     = contains(keys(var.instances), var.http_active_key)
    error_message = "http_active_key 는 instances 의 키여야 한다."
  }
}

variable "instance_type" {
  description = "Media 상자 인스턴스 유형 — arm64(Graviton)만. 기본값 없음(prod 값 c8gn.large · kty 확정 — 루트 tfvars)."
  type        = string

  # 입력 단계의 거름 — 계열 이름의 세대 숫자 바로 뒤가 g(Graviton 표지: c8gn ·
  # c7g · t4g · m7g · im4gn). 확정 판정은 plan 때 AWS 가 답하는 지원
  # 아키텍처다(main.tf 의 사전 조건).
  validation {
    condition     = can(regex("^(a1|[a-z]+[0-9]+g[a-z0-9-]*)\\.[a-z0-9]+$", var.instance_type))
    error_message = "instance_type 은 Graviton(arm64) 계열이어야 한다(예: c8gn.large · c7g.large · t4g.small)."
  }
}

variable "root_volume_size" {
  description = "루트 볼륨 크기(GiB, gp3) — 녹화 · DVR 이 여기에 쌓인다(prod 값 150 · kty 확정)."
  type        = number

  validation {
    condition     = var.root_volume_size >= 8 && floor(var.root_volume_size) == var.root_volume_size
    error_message = "root_volume_size 는 8 이상의 정수(GiB)여야 한다."
  }
}

variable "root_volume_throughput" {
  description = "루트 볼륨 gp3 처리량(MiB/s). 기본 125 는 gp3 기본값이다."
  type        = number
  default     = 125

  # gp3 는 125 부터이고 IOPS 당 0.25 MiB/s 까지다. 이 모듈은 IOPS 를 기본
  # 3,000 으로 두므로 상한은 750 이다(AWS EBS 사용자 안내서 「General Purpose
  # SSD volumes」 — 2026-10-23 확인).
  validation {
    condition = (
      var.root_volume_throughput >= 125 && var.root_volume_throughput <= 750 &&
      floor(var.root_volume_throughput) == var.root_volume_throughput
    )
    error_message = "root_volume_throughput 은 125 부터 750 까지의 정수(MiB/s)여야 한다(IOPS 3,000 × 0.25)."
  }
}

variable "ami_ssm_parameter_name" {
  description = "AMI id 를 읽을 SSM 공개 파라미터(AL2023 arm64). 이미 떠 있는 키는 AMI 가 바뀌어도 교체하지 않는다."
  type        = string
  default     = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

variable "target_group_arns" {
  description = "대상 그룹 ARN — ingest(NLB UDP) · live · control · playback(내부 ALB). platform 출력."
  type = object({
    ingest   = string
    live     = string
    control  = string
    playback = string
  })
}

variable "internal_alb_sg_id" {
  description = "내부 ALB SG id(platform 출력) — Media 인그레스 출처 · 이그레스 규칙을 붙일 SG."
  type        = string
}

variable "nlb_sg_id" {
  description = "NLB SG id(platform 출력) — Media 인그레스 출처 · 이그레스 규칙을 붙일 SG."
  type        = string
}

variable "rds_sg_id" {
  description = "RDS SG id(런타임 1단 출력) — Media 이그레스 5432 와 RDS 인그레스 ← Media 의 한 쌍."
  type        = string
}

variable "s3_prefix_list_id" {
  description = "S3 관리형 prefix list id(network 출력) — 이그레스 443."
  type        = string
}

variable "srt_port" {
  description = "SRT 수신 UDP 포트(NLB 대상 · MediaMTX srtAddress)."
  type        = number
  default     = 8890
}

variable "proxy_ports" {
  description = "nginx 듣기 포트 넷(live · playback · control · health). 1024 이상 · 서로 다름 · 응답기(8080 · 8081)와 MediaMTX 루프백(8888 · 9996 · 9997) 밖."
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
  description = "nginx 헬스 경로(/ 로 시작) — 대상 그룹 헬스체크 경로와 같아야 한다."
  type        = string

  validation {
    condition     = can(regex("^/[A-Za-z0-9._/-]*$", var.media_health_path))
    error_message = "media_health_path 는 / 로 시작하는 경로여야 한다."
  }
}

variable "control_upstream" {
  description = "제어면 상류 <호스트>:<포트>. 빈 값이면 nginx 가 제어면 경로를 404 로 답한다(I1-6 배포 전 기본)."
  type        = string
  default     = ""

  validation {
    condition     = var.control_upstream == "" || can(regex("^[A-Za-z0-9.-]+:[0-9]{1,5}$", var.control_upstream))
    error_message = "control_upstream 은 빈 값이거나 <호스트>:<포트> 여야 한다."
  }
}

variable "janitor_enabled" {
  description = "녹화 정리 janitor 를 렌더한다(사이드카 이미지 재사용 — kty 확정 기본 켬)."
  type        = bool
  default     = true
}

variable "bundle_image" {
  description = "설치 묶음 이미지 <ECR 저장소>@sha256:<64자>(스크립트 · 상자에서 렌더하는 템플릿)."
  type        = string

  validation {
    condition     = can(regex("^[^@]+@sha256:[0-9a-f]{64}$", var.bundle_image))
    error_message = "bundle_image 는 <저장소>@sha256:<16진 64자> 꼴이어야 한다."
  }
}

variable "mediamtx_image" {
  description = "MediaMTX(mtxhook) 이미지 <ECR 저장소>@sha256:<64자>."
  type        = string

  validation {
    condition     = can(regex("^[^@]+@sha256:[0-9a-f]{64}$", var.mediamtx_image))
    error_message = "mediamtx_image 는 <저장소>@sha256:<16진 64자> 꼴이어야 한다."
  }
}

variable "nginx_image" {
  description = "nginx 이미지 <ECR 저장소>@sha256:<64자>."
  type        = string

  validation {
    condition     = can(regex("^[^@]+@sha256:[0-9a-f]{64}$", var.nginx_image))
    error_message = "nginx_image 는 <저장소>@sha256:<16진 64자> 꼴이어야 한다."
  }
}

variable "sidecar_image" {
  description = "사이드카 이미지 <ECR 저장소>@sha256:<64자>(janitor 도 이 이미지)."
  type        = string

  validation {
    condition     = can(regex("^[^@]+@sha256:[0-9a-f]{64}$", var.sidecar_image))
    error_message = "sidecar_image 는 <저장소>@sha256:<16진 64자> 꼴이어야 한다."
  }
}

variable "loopback_sender_image" {
  description = "루프백 송출기 이미지 <ECR 저장소>@sha256:<64자>(media.env LOOPBACK_SENDER_IMAGE)."
  type        = string

  validation {
    condition     = can(regex("^[^@]+@sha256:[0-9a-f]{64}$", var.loopback_sender_image))
    error_message = "loopback_sender_image 는 <저장소>@sha256:<16진 64자> 꼴이어야 한다."
  }
}

variable "ingest_auth_image" {
  description = "응답기 이미지 <ECR 저장소>@sha256:<64자>. 빈 값이면 렌더하지 않는다(M15 가 채움)."
  type        = string
  default     = ""

  validation {
    condition     = var.ingest_auth_image == "" || can(regex("^[^@]+@sha256:[0-9a-f]{64}$", var.ingest_auth_image))
    error_message = "ingest_auth_image 는 빈 값이거나 <저장소>@sha256:<16진 64자> 꼴이어야 한다."
  }
}

variable "rds_endpoint" {
  description = "RDS 엔드포인트 호스트(런타임 1단 출력) — 사이드카 · janitor POSTGRES_HOST."
  type        = string
}

variable "rds_db_name" {
  description = "DB 이름(런타임 1단 출력) — 사이드카 · janitor POSTGRES_DB."
  type        = string
}

variable "rds_resource_id" {
  description = "RDS 리소스 id(런타임 1단 출력) — rds-db:connect Resource 의 dbuser 자리."
  type        = string

  validation {
    condition     = can(regex("^(db|cluster)-[A-Za-z0-9]+$", var.rds_resource_id))
    error_message = "rds_resource_id 는 db-<영숫자> · cluster-<영숫자> 꼴이어야 한다(와일드카드 금지)."
  }
}

variable "rewind_bucket_arn" {
  description = "rewind 버킷 ARN(런타임 1단 출력, arn:aws:s3:::<이름>). 사이드카 S3_BUCKET 은 여기서 꺼낸 이름이다."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:s3:::[a-z0-9][a-z0-9.-]+$", var.rewind_bucket_arn))
    error_message = "rewind_bucket_arn 은 arn:aws:s3:::<버킷 이름> 꼴이어야 한다(객체 경로 · 와일드카드 금지)."
  }
}

# 기본값은 prod 영상 CDN 주소다 — 목록 줄 = 앞머리 + "/" + S3 키(dvr/…), CDN 의
# /dvr/* 가 rewind 버킷이다(설계 4-5).
variable "rewind_public_base_url" {
  description = "사이드카 REWIND_PUBLIC_BASE_URL — 되감기 목록 URI 앞머리(https 절대 URL · 끝 / 없음). 버킷이 있으면 사이드카가 이 값 없이 기동하지 않는다."
  type        = string
  default     = "https://media.pokeclip.com"

  # 사이드카 검증(media/internal/config/config.go)보다 좁게 — https · 호스트 ·
  # 선택 포트 · . 으로 시작하지 않는 경로 조각(dot 세그먼트 · 끝 / · query ·
  # fragment 거부).
  validation {
    condition     = can(regex("^https://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?(/[A-Za-z0-9_~-][A-Za-z0-9._~-]*)*$", var.rewind_public_base_url))
    error_message = "rewind_public_base_url 은 https://<호스트>[/<경로>] 꼴이어야 한다(끝 / · query · fragment · . 으로 시작하는 경로 조각 금지)."
  }
}

variable "cdn_secret_arn" {
  description = "CDN 비밀 ARN(prod/secrets 출력 — 값 아님)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]+:secret:[A-Za-z0-9/_+=.@-]+$", var.cdn_secret_arn))
    error_message = "cdn_secret_arn 은 Secrets Manager 비밀 ARN 이어야 한다(와일드카드 금지)."
  }
}

variable "test_stream_key_secret_arn" {
  description = "시험 스트림 키 비밀 ARN(prod/secrets 출력 — 값 아님)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]+:secret:[A-Za-z0-9/_+=.@-]+$", var.test_stream_key_secret_arn))
    error_message = "test_stream_key_secret_arn 은 Secrets Manager 비밀 ARN 이어야 한다(와일드카드 금지)."
  }
}

variable "media_secret_arns" {
  description = "그 밖에 Media 상자가 읽는 비밀 ARN(런타임 1단 출력 media_secret_arns — INTERNAL_API_TOKEN 등)."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for arn in var.media_secret_arns :
      can(regex("^arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]+:secret:[A-Za-z0-9/_+=.@-]+$", arn))
    ])
    error_message = "media_secret_arns 는 Secrets Manager 비밀 ARN 이어야 한다(와일드카드 금지)."
  }
}

variable "docker_version" {
  description = "AL2023 저장소의 docker 패키지 판(예: 25.0.16)."
  type        = string

  validation {
    condition     = can(regex("^[0-9][0-9A-Za-z.+_-]*$", var.docker_version))
    error_message = "docker_version 은 판 문자열이어야 한다."
  }
}

variable "ecr_credential_helper_version" {
  description = "AL2023 저장소의 amazon-ecr-credential-helper 패키지 판-릴리스(예: 0.12.0-3.amzn2023)."
  type        = string

  validation {
    condition     = can(regex("^[0-9][0-9A-Za-z.+_-]*$", var.ecr_credential_helper_version))
    error_message = "ecr_credential_helper_version 은 판 문자열이어야 한다."
  }
}

variable "compose_version" {
  description = "docker compose 플러그인 판(공식 릴리스 태그에서 v 를 뺀 값). media.env 의 기대 판과 같다."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", var.compose_version))
    error_message = "compose_version 은 X.Y.Z 꼴이어야 한다."
  }
}

variable "compose_sha256" {
  description = "compose 플러그인 docker-compose-linux-aarch64 바이너리의 sha256(공식 릴리스 .sha256)."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.compose_sha256))
    error_message = "compose_sha256 은 16진 64자여야 한다."
  }
}

variable "denylist_parameter_name" {
  description = "응답기 거부 목록 SSM 파라미터 이름(후보 — M15 계획 게이트에서 확정되면 맞춘다)."
  type        = string
  default     = "/pokeclip/prod/media/ingest-auth-deny-tokens"

  validation {
    condition     = can(regex("^(/[A-Za-z0-9._-]+)+$", var.denylist_parameter_name))
    error_message = "denylist_parameter_name 은 /<토막>/… 꼴이어야 한다."
  }
}

variable "stream_key_resolve_url" {
  description = "응답기의 4C(스트림 키 판정) 주소 http://<호스트>:<포트>(예: http://auth.pokeclip.internal:<auth 포트>). 응답기 모드(ingest_auth_image 비어 있지 않음)에서만 값이 필수다(빈 값 거부는 루트 배선과 함께 — M9 후속). 키는 두 모드 모두 템플릿에 넘긴다 — templatefile() 은 쓰지 않는 갈래의 변수도 요구한다(옛 모드는 빈 값)."
  type        = string
  default     = ""

  # 문자 집합으로 사용자 정보 · 경로 · 질의 · 조각 · $ · 공백 · # 이 모두 막힌다.
  validation {
    condition     = var.stream_key_resolve_url == "" || can(regex("^http://[A-Za-z0-9.-]+:[0-9]+$", var.stream_key_resolve_url))
    error_message = "stream_key_resolve_url 은 빈 값이거나 http://<호스트>:<포트> 여야 한다(사용자 정보 · 경로 · 질의 · 조각 금지)."
  }
}

variable "ingest_auth_ip_limit" {
  description = "응답기 IP 한도 \"on\" · \"off\"(ingest-auth.env 의 INGEST_AUTH_IP_LIMIT). 두 모드 모두 렌더하고 응답기 모드에서만 읽힌다."
  type        = string
  default     = "on"

  validation {
    condition     = contains(["on", "off"], var.ingest_auth_ip_limit)
    error_message = "ingest_auth_ip_limit 은 \"on\" 또는 \"off\" 여야 한다."
  }
}
