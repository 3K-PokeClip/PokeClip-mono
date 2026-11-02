variable "name_prefix" {
  description = "자원 이름 접두(예: pokeclip-prod). 서비스 · 태스크 · SG · 로그 그룹은 <name_prefix>-<name>."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*$", var.name_prefix))
    error_message = "name_prefix 는 소문자 · 숫자 · - 로, 소문자로 시작해야 한다."
  }
}

variable "name" {
  description = "서비스 이름(예: auth). 컨테이너 이름 · Cloud Map 서비스 이름(<name>.<네임스페이스>)이 된다."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*$", var.name))
    error_message = "name 은 DNS 이름 조각(소문자 · 숫자 · -, 소문자로 시작)이어야 한다."
  }
}

variable "iam_path" {
  description = "실행 · 작업 역할의 IAM 경로(prod = /pokeclip/prod/ — 2-3 판정의 prod 주체 식별 기준)."
  type        = string

  validation {
    condition     = can(regex("^/([A-Za-z0-9_+=,.@-]+/)+$", var.iam_path))
    error_message = "iam_path 는 / 로 시작하고 / 로 끝나는 IAM 경로여야 한다(예: /pokeclip/prod/)."
  }
}

variable "cluster_arn" {
  description = "서비스를 둘 ECS 클러스터 ARN."
  type        = string
}

variable "vpc_id" {
  description = "서비스 SG 의 VPC id."
  type        = string
}

variable "subnet_ids" {
  description = "태스크를 둘 프라이빗 서브넷 id 목록."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) > 0
    error_message = "subnet_ids 는 비어 있으면 안 된다."
  }
}

variable "image" {
  description = "컨테이너 이미지(ECR — <레지스트리>/<저장소>:<태그> 또는 @sha256:<다이제스트>). 실행 역할의 끌어오기 권한이 이 저장소로 좁혀진다."
  type        = string

  validation {
    condition     = can(regex(local.ecr_image_pattern, var.image))
    error_message = "image 는 ECR 이미지(<12자리>.dkr.ecr.<리전>.amazonaws.com/<저장소>:<태그> 또는 @sha256:<다이제스트>)여야 한다."
  }
}

variable "cpu" {
  description = "태스크 CPU 단위(Fargate 값 — 256 · 512 · 1024 · 2048 · 4096 · 8192 · 16384)."
  type        = number

  validation {
    condition     = contains([256, 512, 1024, 2048, 4096, 8192, 16384], var.cpu)
    error_message = "cpu 는 Fargate 가 받는 값(256 · 512 · 1024 · 2048 · 4096 · 8192 · 16384)이어야 한다."
  }
}

variable "memory" {
  description = "태스크 메모리(MiB). cpu 와의 조합은 ECS 가 검사한다."
  type        = number

  validation {
    condition     = var.memory > 0 && floor(var.memory) == var.memory
    error_message = "memory 는 양의 정수(MiB)여야 한다."
  }
}

variable "port" {
  description = "컨테이너 포트(TCP) — 대상 그룹 연결 · Cloud Map 대상 포트."
  type        = number

  validation {
    condition     = var.port >= 1 && var.port <= 65535 && floor(var.port) == var.port
    error_message = "port 는 1 부터 65535 까지의 정수여야 한다."
  }
}

variable "health_check_grace" {
  description = "대상 그룹 헬스체크 유예(초). target_group_arns 가 비면 쓰지 않는다."
  type        = number

  validation {
    condition     = var.health_check_grace >= 0 && floor(var.health_check_grace) == var.health_check_grace
    error_message = "health_check_grace 는 0 이상의 정수(초)여야 한다."
  }
}

variable "deployment_minimum_healthy_percent" {
  description = "재배포 때 살아 있어야 할 태스크 비율(%, 0 부터 100). null 이면 넘기지 않는다(ECS 기본 100)."
  type        = number
  default     = null

  validation {
    condition = var.deployment_minimum_healthy_percent == null ? true : (
      var.deployment_minimum_healthy_percent >= 0 &&
      var.deployment_minimum_healthy_percent <= 100 &&
      floor(var.deployment_minimum_healthy_percent) == var.deployment_minimum_healthy_percent
    )
    error_message = "deployment_minimum_healthy_percent 는 null 이거나 0 부터 100 까지의 정수여야 한다."
  }
}

variable "deployment_maximum_percent" {
  description = "재배포 때 띄울 수 있는 태스크 비율 상한(%, 100 부터 200). 100 이면 옛 태스크를 내린 뒤 새 태스크를 띄운다. null 이면 넘기지 않는다(ECS 기본 200)."
  type        = number
  default     = null

  validation {
    condition = var.deployment_maximum_percent == null ? true : (
      var.deployment_maximum_percent >= 100 &&
      var.deployment_maximum_percent <= 200 &&
      floor(var.deployment_maximum_percent) == var.deployment_maximum_percent
    )
    error_message = "deployment_maximum_percent 는 null 이거나 100 부터 200 까지의 정수여야 한다."
  }
}

variable "log_retention_days" {
  description = "CloudWatch Logs 그룹 보존 일수(CloudWatch 가 받는 값 — 0(영구)은 받지 않는다)."
  type        = number

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days 는 CloudWatch Logs 보존 값(1 · 3 · 5 · 7 · 14 · 30 · … · 3653) 중 하나여야 한다."
  }
}

variable "env" {
  description = "컨테이너 환경 변수(비밀 아닌 값만)."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "컨테이너 비밀(환경 변수 이름 → Secrets Manager 비밀 ARN). 실행 역할의 읽기 권한이 이 ARN 집합으로 한정된다. 값은 들지 않는다."
  type        = map(string)
  default     = {}

  # 정책 Resource 가 입력 그대로라, 와일드카드 · ARN 아닌 값을 여기서 막는다.
  validation {
    condition = alltrue([
      for arn in values(var.secrets) :
      can(regex("^arn:aws:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+$", arn))
    ])
    error_message = "secrets 의 값은 와일드카드 없는 Secrets Manager 비밀 ARN 이어야 한다."
  }

  validation {
    condition     = length(setintersection(keys(var.secrets), keys(var.env))) == 0
    error_message = "secrets 와 env 에 같은 이름이 있으면 안 된다."
  }
}

variable "target_group_arns" {
  description = "서비스를 붙일 대상 그룹 ARN 목록(컨테이너 <name>:<port>)."
  type        = list(string)
  default     = []
}

variable "discovery_namespace_id" {
  description = "Cloud Map 사설 DNS 네임스페이스 id(pokeclip.internal)."
  type        = string
}

variable "egress_targets" {
  description = "서비스 SG 이그레스(설계 5-5). 키마다 TCP 규칙 하나 — 상대는 cidr_ipv4 · prefix_list_id · security_group_id 중 정확히 하나. 인그레스는 호출자가 만든다."
  type = map(object({
    port              = number
    cidr_ipv4         = optional(string)
    prefix_list_id    = optional(string)
    security_group_id = optional(string)
    description       = optional(string)
  }))

  validation {
    condition = alltrue([
      for target in values(var.egress_targets) :
      length([for destination in [target.cidr_ipv4, target.prefix_list_id, target.security_group_id] : destination if destination != null]) == 1
    ])
    error_message = "egress_targets 의 원소마다 cidr_ipv4 · prefix_list_id · security_group_id 중 정확히 하나를 둬야 한다."
  }

  validation {
    condition = alltrue([
      for target in values(var.egress_targets) :
      target.port >= 1 && target.port <= 65535 && floor(target.port) == target.port
    ])
    error_message = "egress_targets 의 port 는 1 부터 65535 까지의 정수여야 한다."
  }
}

variable "task_role_statements" {
  description = "작업 역할 정책 문장(Allow). 비면 작업 역할은 정책 없이 만든다. resources 에 맨 * 는 받지 않는다(접두 와일드카드 …/x/* 는 받음)."
  type = list(object({
    sid       = string
    actions   = list(string)
    resources = list(string)
  }))
  default = []

  validation {
    condition = alltrue([
      for statement in var.task_role_statements :
      !contains(statement.resources, "*")
    ])
    error_message = "task_role_statements 의 resources 에 맨 * 는 둘 수 없다."
  }

  validation {
    condition = alltrue([
      for statement in var.task_role_statements :
      length(statement.actions) > 0 && length(statement.resources) > 0
    ])
    error_message = "task_role_statements 의 문장마다 actions · resources 가 하나 이상이어야 한다."
  }

  validation {
    condition = alltrue([
      for statement in var.task_role_statements : can(regex("^[A-Za-z0-9]+$", statement.sid))
      ]) && length(distinct([
        for statement in var.task_role_statements : statement.sid
    ])) == length(var.task_role_statements)
    error_message = "task_role_statements 의 sid 는 영숫자만이고 서로 달라야 한다."
  }
}
