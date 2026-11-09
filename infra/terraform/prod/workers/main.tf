# 일꾼 render · upload 를 prod 클러스터에 서비스 하나씩 둔다(POK-408). 클러스터 ·
# Cloud Map 네임스페이스 · clip · auth SG 는 platform 이 만들고, 이 루트는 그것을
# 읽어 같은 자리에 서비스를 두고 상대 SG 인그레스(clip ← 일꾼 · auth ← upload)를
# 갖는다 — Media ↔ auth 규칙을 media 루트가 갖는 것과 같은 꼴(kty 2026-10-25 (나)).
# 일꾼은 웹 서버가 없어 포트 · 대상 그룹 · 일꾼 SG 인그레스가 없다.

locals {
  name_prefix        = "pokeclip-prod"
  iam_path           = "/pokeclip/prod/"
  log_retention_days = 30

  # platform 네임스페이스 이름(platform services.tf discovery_domain). platform 이
  # 이름을 출력하지 않아 상수로 두고, 아래 조회의 id 로 실물과 묶는다(검사 6).
  discovery_domain = "pokeclip.internal"

  clip_base_url = "http://clip.${local.discovery_domain}:${local.platform.service_ports.clip}"
  auth_base_url = "http://auth.${local.discovery_domain}:${local.platform.service_ports.auth}"

  worker_images = {
    for key, digest in var.image_digests : key => "${local.repo_urls[key]}@${digest}"
  }

  # 런타임 1단이 일꾼에 주는 env 이름(CONTRACT-runtime.md 「앱이 읽는 env 이름」).
  # 같음으로 비교한다 — 이름이 늘면 비밀이든 아니든 태스크 정의에 실리기 전에
  # plan 이 멈추고, 계약 변경은 이 루트와 한 PR 이 된다.
  runtime_env_names = {
    render = ["RENDER_QUEUE_URL", "RENDER_VISIBILITY_TIMEOUT", "THUMBNAIL_QUEUE_URL", "THUMBNAIL_VISIBILITY_TIMEOUT"]
    upload = ["UPLOAD_QUEUE_URL", "UPLOAD_VISIBILITY_TIMEOUT"]
  }

  # 작업 역할 대상 키(CONTRACT-runtime.md 「일꾼 작업 역할 대상」).
  task_resource_keys = {
    render = ["clips_bucket_arn", "render_queue_arn", "segment_bucket_arn", "thumbnail_queue_arn"]
    upload = ["clips_bucket_arn", "upload_queue_arn"]
  }

  # 큐 URL env → 같은 큐의 작업 역할 대상 키(검사 2).
  queue_url_arn_keys = {
    render = { RENDER_QUEUE_URL = "render_queue_arn", THUMBNAIL_QUEUE_URL = "thumbnail_queue_arn" }
    upload = { UPLOAD_QUEUE_URL = "upload_queue_arn" }
  }

  # 키가 없으면 속성 오류가 아니라 아래 검사의 문구로 멈추게 빈 맵으로 둔다.
  runtime_env = {
    for key in keys(local.runtime_env_names) : key => try(local.runtime.app_env[key], {})
  }
  worker_secrets = {
    for key in keys(local.runtime_env_names) : key => try(local.runtime.app_secret_arns[key], {})
  }
  task_resource_arns = {
    for key in keys(local.task_resource_keys) : key => try(local.runtime.app_task_resource_arns[key], {})
  }

  # 사진 보고가 401 이면 일꾼이 메시지를 그 자리에서 지운다 — clip 과 같은 그릇을
  # 읽어야 한다(검사 3).
  clip_token_arn = try(local.runtime.app_secret_arns.clip.INTERNAL_API_TOKEN, "")

  # 주소는 Cloud Map 이름이다. 빠뜨리면 일꾼이 localhost 로 붙는다.
  worker_env = {
    render = merge(local.runtime_env.render, {
      CLIP_BASE_URL = local.clip_base_url
    })
    upload = merge(local.runtime_env.upload, {
      CLIP_BASE_URL = local.clip_base_url
      AUTH_BASE_URL = local.auth_base_url
    })
  }

  # 앞 루트 출력에 키가 없으면(엔드포인트 앞의 network 상태) 속성 오류가 아니라
  # 계약 검사 7 · 8 의 문구로 멈추게 null 로 둔다.
  interface_endpoint_sg_id       = try(local.network.interface_endpoint_sg_id, null)
  interface_endpoint_services    = try(local.network.interface_endpoint_services, null)
  interface_endpoint_private_dns = try(local.network.interface_endpoint_private_dns, null)

  # render 의 AWS API(SQS · CloudWatch Logs · ECR API · ECR 등록소 · Secrets
  # Manager)가 가는 VPC 인터페이스 엔드포인트(prod/network). 하나라도 빠지면
  # render 가 기동 · 수신하지 못한다.
  render_endpoint_services = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]

  # platform common_egress 와 같은 값. upload 의 AWS API 는 NAT 로 나가고, 조각 ·
  # 영상 · ECR 레이어는 S3 게이트웨이로 간다.
  common_egress = {
    aws_api = { port = 443, cidr_ipv4 = "0.0.0.0/0", description = "AWS APIs via NAT" }
    s3      = { port = 443, prefix_list_id = local.network.s3_prefix_list_id, description = "S3 gateway endpoint" }
  }

  # render 는 인터넷에 나갈 일이 없다 — AWS API 는 엔드포인트 SG 로만 보낸다
  # (true). false 는 NAT 로 되돌리는 되돌리기 전용 갈래다. 두 갈래의 꼴이 달라
  # 조건식 대신 문자열 키로 고른다.
  render_aws_egress = {
    "true" = {
      aws_endpoints = { port = 443, security_group_id = local.interface_endpoint_sg_id, description = "AWS APIs via VPC interface endpoints" }
    }
    "false" = {
      aws_api = local.common_egress.aws_api
    }
  }[tostring(var.render_egress_via_endpoints)]

  egress_targets = {
    render = merge(local.render_aws_egress, {
      s3   = local.common_egress.s3
      clip = { port = local.platform.service_ports.clip, security_group_id = local.platform.service_sg_ids.clip, description = "render to clip" }
    })
    # googleapis 443 도 aws_api 규칙으로 나간다 — SG 는 FQDN 을 거르지 못한다.
    upload = merge(local.common_egress, {
      aws_api = merge(local.common_egress.aws_api, { description = "AWS APIs and YouTube via NAT" })
      clip    = { port = local.platform.service_ports.clip, security_group_id = local.platform.service_sg_ids.clip, description = "upload to clip" }
      auth    = { port = local.platform.service_ports.auth, security_group_id = local.platform.service_sg_ids.auth, description = "upload to auth resolve" }
    })
  }

  # 작업 역할 = 일꾼 코드가 부르는 동작만(결정 135). 계약 표 그대로이고 근거
  # 파일:행은 CONTRACT-runtime.md 「일꾼 작업 역할 대상」 에 있다. ListBucket 은
  # 없는 조각을 403 이 아니라 404 로 받으려는 권한이다(LIST 는 부르지 않음).
  render_arns = local.task_resource_arns.render
  upload_arns = local.task_resource_arns.upload

  task_role_statements = {
    render = [
      {
        sid       = "JobQueues"
        actions   = ["sqs:ChangeMessageVisibility", "sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = [lookup(local.render_arns, "render_queue_arn", ""), lookup(local.render_arns, "thumbnail_queue_arn", "")]
      },
      {
        sid       = "SourceObjects"
        actions   = ["s3:GetObject"]
        resources = ["${lookup(local.render_arns, "segment_bucket_arn", "")}/*", "${lookup(local.render_arns, "clips_bucket_arn", "")}/*"]
      },
      {
        sid       = "SourceList"
        actions   = ["s3:ListBucket"]
        resources = [lookup(local.render_arns, "segment_bucket_arn", ""), lookup(local.render_arns, "clips_bucket_arn", "")]
      },
      {
        sid       = "ClipObjects"
        actions   = ["s3:DeleteObject", "s3:PutObject"]
        resources = ["${lookup(local.render_arns, "clips_bucket_arn", "")}/*"]
      },
    ]
    upload = [
      {
        sid       = "UploadQueue"
        actions   = ["sqs:ChangeMessageVisibility", "sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = [lookup(local.upload_arns, "upload_queue_arn", "")]
      },
      {
        sid       = "ClipRead"
        actions   = ["s3:GetObject"]
        resources = ["${lookup(local.upload_arns, "clips_bucket_arn", "")}/*"]
      },
    ]
  }
}

data "aws_service_discovery_dns_namespace" "internal" {
  name = local.discovery_domain
  type = "DNS_PRIVATE"
}

# 앞 루트 출력을 env · 정책 · SG 규칙에 쓰기 전에 꼴을 본다(platform
# remote_state.tf 의 runtime_contract 와 같은 자리). 모듈 · 규칙이 이 검사를
# 기다린다.
resource "terraform_data" "runtime_contract" {
  lifecycle {
    # 검사 1 — env 이름 허용 목록(같음 비교).
    precondition {
      condition = alltrue([
        for key, names in local.runtime_env_names : sort(keys(local.runtime_env[key])) == sort(names)
      ])
      error_message = "런타임 출력 app_env 의 render · upload 이름이 계약과 다르다 — render 는 RENDER_QUEUE_URL · THUMBNAIL_QUEUE_URL · RENDER_VISIBILITY_TIMEOUT · THUMBNAIL_VISIBILITY_TIMEOUT, upload 는 UPLOAD_QUEUE_URL · UPLOAD_VISIBILITY_TIMEOUT 정확히다. 이름을 더하려면 이 루트와 한 PR 로 계약을 고친다."
    }

    # 검사 2 — 숨김 시간은 Spring Duration 의 초 꼴(600s). 단위 없는 600 은
    # 밀리초로 읽힌다.
    precondition {
      condition = alltrue(flatten([
        for env in values(local.runtime_env) : [
          for name, value in env : can(regex("^[0-9]+s$", value)) if endswith(name, "_VISIBILITY_TIMEOUT")
        ]
      ]))
      error_message = "런타임 출력 app_env 의 *_VISIBILITY_TIMEOUT 이 초 단위 꼴(^[0-9]+s$ — 예: 600s)이 아니다."
    }

    # 검사 2 — 받는 큐(URL)와 작업 역할이 허락한 큐(ARN)가 같은 큐다. 다르면
    # 받기가 권한 거부로 실패한다.
    precondition {
      condition = alltrue(flatten([
        for key, pairs in local.queue_url_arn_keys : [
          for url_name, arn_key in pairs :
          try(regex("[^/]+$", local.runtime_env[key][url_name]), "") != "" &&
          try(regex("[^/]+$", local.runtime_env[key][url_name]), "") == try(regex("[^:]+$", local.task_resource_arns[key][arn_key]), "")
        ]
      ]))
      error_message = "런타임 출력 app_env 의 큐 URL(RENDER_QUEUE_URL · THUMBNAIL_QUEUE_URL · UPLOAD_QUEUE_URL)의 큐 이름이 app_task_resource_arns 의 같은 큐 ARN(render_queue_arn · thumbnail_queue_arn · upload_queue_arn)의 끝 조각과 다르다."
    }

    # 검사 3 — 실행 역할은 INTERNAL_API_TOKEN 그릇 하나만 읽는다.
    precondition {
      condition = alltrue([
        for secrets in values(local.worker_secrets) : sort(keys(secrets)) == sort(["INTERNAL_API_TOKEN"])
      ])
      error_message = "런타임 출력 app_secret_arns 의 render · upload 가 INTERNAL_API_TOKEN 하나가 아니다."
    }

    # 검사 3 — 그 그릇이 clip 이 읽는 것과 같다(CONTRACT-runtime.md 「앱 서버 넷과
    # 같은 그릇」 을 plan 이 강제). 같은 그릇이어도 회전 직후의 값 차이는 apply
    # 직전 읽기가 막는다(README 「apply 직전 읽기」).
    precondition {
      condition = local.clip_token_arn != "" && alltrue([
        for secrets in values(local.worker_secrets) : lookup(secrets, "INTERNAL_API_TOKEN", "") == local.clip_token_arn
      ])
      error_message = "런타임 출력 app_secret_arns 의 render · upload INTERNAL_API_TOKEN 이 clip 의 INTERNAL_API_TOKEN 과 같은 ARN 이 아니거나 clip 값이 없다 — 일꾼의 사진 보고가 401 을 받으면 메시지가 그 자리에서 지워진다."
    }

    # 검사 4 — 작업 역할 대상 키 · 값 꼴. 값이 정책 Resource 에 그대로 들어간다.
    precondition {
      condition = alltrue([
        for key, names in local.task_resource_keys : sort(keys(local.task_resource_arns[key])) == sort(names)
      ])
      error_message = "런타임 출력 app_task_resource_arns 의 render · upload 키가 계약과 다르다 — render 는 render_queue_arn · thumbnail_queue_arn · segment_bucket_arn · clips_bucket_arn, upload 는 upload_queue_arn · clips_bucket_arn 정확히다."
    }

    # 일 큐는 표준 큐라 .fifo 를 받지 않는다(prod/runtime job_queues.tf).
    precondition {
      condition = alltrue(flatten([
        for arns in values(local.task_resource_arns) : [
          for key, arn in arns :
          endswith(key, "_bucket_arn") ? can(regex("^arn:aws:s3:::[a-z0-9][a-z0-9.-]+$", arn)) : can(regex("^arn:aws:sqs:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+$", arn))
        ]
      ]))
      error_message = "런타임 출력 app_task_resource_arns 의 render · upload 값이 와일드카드 없는 S3 버킷 ARN · 표준 SQS 큐 ARN(.fifo 아님)이 아니다."
    }

    # 검사 5 — 상대 SG.
    precondition {
      condition = alltrue([
        for name in ["auth", "clip"] : can(regex("^sg-[A-Za-z0-9]+$", try(local.platform.service_sg_ids[name], "")))
      ])
      error_message = "platform 출력 service_sg_ids 의 auth · clip 이 없거나 SG id(sg-…)가 아니다."
    }

    # 검사 6 — 주소의 상수 도메인이 platform 네임스페이스다.
    precondition {
      condition     = data.aws_service_discovery_dns_namespace.internal.id == try(local.platform.discovery_namespace_id, "")
      error_message = "사설 DNS 네임스페이스 pokeclip.internal 의 id 가 platform 출력 discovery_namespace_id 와 다르다 — 일꾼 주소(CLIP_BASE_URL · AUTH_BASE_URL)가 다른 이름 공간을 가리킨다."
    }

    # 검사 7 — render 이그레스의 엔드포인트 SG(true 일 때만 — false 는 되돌리기
    # 갈래라 엔드포인트 출력이 없어도 plan 이 돌아야 한다).
    precondition {
      condition     = !var.render_egress_via_endpoints || can(regex("^sg-[0-9a-f]+$", local.interface_endpoint_sg_id))
      error_message = "network 출력 interface_endpoint_sg_id 가 없거나 SG id(sg-<16진수>)가 아니다 — network 의 인터페이스 엔드포인트를 apply 한 뒤에 돌린다. 되돌리는 중이면 render_egress_via_endpoints = false 로 둔다."
    }

    # 검사 8 — 엔드포인트 다섯과 private DNS(true 일 때만). 하나라도 빠지거나 DNS 가
    # 꺼지면 render 가 기동 · 수신하지 못하는데 plan 은 조용히 통과한다.
    precondition {
      condition = !var.render_egress_via_endpoints || (
        try(length(setsubtract(local.render_endpoint_services, local.interface_endpoint_services)) == 0, false) &&
        local.interface_endpoint_private_dns == true
      )
      error_message = "network 출력 interface_endpoint_services 가 ecr.api · ecr.dkr · logs · secretsmanager · sqs 를 모두 갖지 않거나 interface_endpoint_private_dns 가 true 가 아니다. 되돌리는 중이면 render_egress_via_endpoints = false 로 둔다."
    }
  }
}

module "render" {
  source = "../../modules/ecs_service"

  name_prefix           = local.name_prefix
  name                  = "render"
  iam_path              = local.iam_path
  cluster_arn           = local.platform.ecs_cluster_arn
  vpc_id                = local.network.vpc_id
  subnet_ids            = local.network.private_subnet_ids
  image                 = local.worker_images.render
  cpu                   = var.workers.render.cpu
  memory                = var.workers.render.memory
  ephemeral_storage_gib = var.workers.render.ephemeral_storage_gib
  log_retention_days    = local.log_retention_days
  env                   = local.worker_env.render
  secrets               = local.worker_secrets.render
  # 대상 그룹이 없어 유예는 쓰이지 않는다(ECS API 가 대상 그룹 없이 받지 않음).
  target_group_arns      = []
  health_check_grace     = 0
  discovery_namespace_id = local.platform.discovery_namespace_id
  egress_targets         = local.egress_targets.render
  task_role_statements   = local.task_role_statements.render

  depends_on = [terraform_data.runtime_contract]
}

module "upload" {
  source = "../../modules/ecs_service"

  name_prefix           = local.name_prefix
  name                  = "upload"
  iam_path              = local.iam_path
  cluster_arn           = local.platform.ecs_cluster_arn
  vpc_id                = local.network.vpc_id
  subnet_ids            = local.network.private_subnet_ids
  image                 = local.worker_images.upload
  cpu                   = var.workers.upload.cpu
  memory                = var.workers.upload.memory
  ephemeral_storage_gib = var.workers.upload.ephemeral_storage_gib
  log_retention_days    = local.log_retention_days
  env                   = local.worker_env.upload
  secrets               = local.worker_secrets.upload
  # 대상 그룹이 없어 유예는 쓰이지 않는다.
  target_group_arns      = []
  health_check_grace     = 0
  discovery_namespace_id = local.platform.discovery_namespace_id
  egress_targets         = local.egress_targets.upload
  task_role_statements   = local.task_role_statements.upload

  depends_on = [terraform_data.runtime_contract]
}

# 일꾼 → clip(렌더 보고 · 사진 보고 · 업로드 start/session/result/heartbeat)의
# 짝. 상대 이그레스는 egress_targets 에 있다. platform 상태가 모르는 별도 규칙
# 자원이라 platform plan 에 드리프트로 보이지 않는다(선례 media 루트의
# auth_from_media — apply 뒤 platform plan 으로 확인).
resource "aws_vpc_security_group_ingress_rule" "clip_from_worker" {
  for_each = {
    render = module.render.sg_id
    upload = module.upload.sg_id
  }

  security_group_id            = local.platform.service_sg_ids.clip
  description                  = "${each.key} to clip"
  ip_protocol                  = "tcp"
  from_port                    = local.platform.service_ports.clip
  to_port                      = local.platform.service_ports.clip
  referenced_security_group_id = each.value

  depends_on = [terraform_data.runtime_contract]
}

# upload → auth(POST /internal/youtube-link/resolve)의 짝.
resource "aws_vpc_security_group_ingress_rule" "auth_from_upload" {
  security_group_id            = local.platform.service_sg_ids.auth
  description                  = "upload to auth resolve"
  ip_protocol                  = "tcp"
  from_port                    = local.platform.service_ports.auth
  to_port                      = local.platform.service_ports.auth
  referenced_security_group_id = module.upload.sg_id

  depends_on = [terraform_data.runtime_contract]
}
