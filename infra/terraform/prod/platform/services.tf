# ECS Fargate 서비스 셋(next · auth · clip)과 클러스터 · Cloud Map · 서비스
# SG 인그레스 · RDS 인그레스 ← auth · clip(설계 3-1 의 ⑧ · 5-5).
#
# chat 두 서버(chat-collector · chat-detector — POK-306)도 같은 모듈로 둔다. 공개
# 노출이 없어 대상 그룹 없이 Cloud Map 이름으로만 받는다. chat ↔ auth · clip
# SG 짝 · RDS ← chat 도 이 루트가 갖는다(계약 개정 — 옛 「런타임 2단 몫」).
#
# Next 는 rewrite 목적지를 빌드 때 굳힌다(web README 「컨테이너 이미지」) —
# 이미지 빌드 인자 AUTH_API_URL · CLIP_API_URL 은 아래 Cloud Map 이름
# (http://auth.pokeclip.internal:8082 · http://clip.pokeclip.internal:8081)이다.

locals {
  name_prefix        = "pokeclip-prod"
  iam_path           = "/pokeclip/prod/"
  log_retention_days = 30
  discovery_domain   = "pokeclip.internal"

  auth_base_url      = "http://auth.${local.discovery_domain}:${var.services.auth.port}"
  clip_base_url      = "http://clip.${local.discovery_domain}:${var.services.clip.port}"
  collector_port     = var.chat_services["chat-collector"].port
  collector_base_url = "http://chat-collector.${local.discovery_domain}:${local.collector_port}"

  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region

  service_images = {
    for name, digest in var.image_digests :
    name => "${data.terraform_remote_state.registry.outputs.repo_urls[name]}@${digest}"
  }

  # 리스너 규칙이 붙은 뒤의 ARN(아래 public_routed_target_groups).
  routed_target_group_arns = terraform_data.public_routed_target_groups.output

  service_target_group_arns = {
    next = [local.routed_target_group_arns["next"]]
    auth = [local.routed_target_group_arns["auth"]]
    clip = [local.routed_target_group_arns["clip"], local.routed_target_group_arns["clip-sse"]]
  }

  # 공개 ALB 뒤 서비스 셋 — 출력 service_sg_ids(소비 루트 계약)와 같다. chat
  # SG 는 모듈 출력을 바로 쓴다.
  service_sg_ids = {
    next = module.next.sg_id
    auth = module.auth.sg_id
    clip = module.clip.sg_id
  }

  db_client_sg_ids = {
    auth             = module.auth.sg_id
    clip             = module.clip.sg_id
    "chat-collector" = module.chat_collector.sg_id
    "chat-detector"  = module.chat_detector.sg_id
  }

  # DB 를 쓰는 서비스 — 런타임 app_db_users 의 키(계약)와 같다.
  db_services = ["auth", "clip", "chat-collector", "chat-detector"]

  db_users = {
    for service in local.db_services : service => local.runtime.app_db_users[service]
  }

  # 같은 사용자 이름이 POSTGRES_USER 와 rds-db:connect Resource 에 들어간다.
  db_env = {
    for service, user in local.db_users : service => merge(
      { POSTGRES_USER = user },
      { for key, value in { POSTGRES_AUTH = "iam", POSTGRES_SSLROOTCERT = var.postgres_sslrootcert } : key => value if var.app_db_auth == "iam" },
    )
  }

  # 이 루트가 정하는 env 가 런타임 app_env 의 같은 이름을 이긴다.
  service_env = {
    next = try(local.runtime.app_env.next, {})
    auth = merge(
      try(local.runtime.app_env.auth, {}),
      local.db_env.auth,
      {
        # 로드밸런서 뒤 — 명시하지 않으면 application.yml 의 none 이 남는다.
        FORWARD_HEADERS_STRATEGY = "native"
        # X-Forwarded-For 를 믿을 소켓 대역 — ALB · next 가 VPC 안이다(app · api 는
        # CloudFront 없이 ALB 직접). 비면 auth 가 아무 프록시도 안 믿는다(POK-316).
        FORWARD_HEADERS_TRUSTED_PROXIES = local.network.vpc_cidr
        # prod 는 처음부터 Secrets Manager(PG 이관 없음) — 접두 · 작업 역할
        # StreamKeySecrets 와 짝이다. application.yml 기본값은 postgres 다.
        STREAM_KEY_SECRET_STORE_TYPE  = "aws"
        STREAM_KEY_SECRET_RETIRE_FROM = "aws"
        STREAM_KEY_SECRET_REF_PREFIX  = var.stream_key_secret_prefix
        CORS_ALLOWED_ORIGINS          = var.cors_allowed_origins
        # 탈퇴 정리(withdrawal-purge) — 비면 부팅이 거부된다.
        CLIP_BASE_URL      = local.clip_base_url
        COLLECTOR_BASE_URL = local.collector_base_url
      },
    )
    clip = merge(
      try(local.runtime.app_env.clip, {}),
      local.db_env.clip,
      {
        CLOUDFRONT_KEY_PAIR_ID = local.secrets.public_key_id
        MEDIA_BASE_URL         = var.media_base_url
        PLAYBACK_COOKIE_DOMAIN = var.playback_cookie_domain
        AUTH_BASE_URL          = local.auth_base_url
        CORS_ALLOWED_ORIGINS   = var.cors_allowed_origins
        # 비어도 부팅은 되지만 채팅 문 셋(수집기 경유)이 503 이다.
        COLLECTOR_BASE_URL = local.collector_base_url
        # 서명 값 셋 중 하나라도 빠지면 부팅을 거부한다(POK-321).
        PLAYBACK_REQUIRED = "true"
      },
    )
    # CHZZK_CLIENT_ID · SOOP_CLIENT_ID(방송 정보 · SOOP 채팅)와 채팅 원본 보관
    # 창고 이름 S3_BUCKET 은 런타임 app_env 가 준다. 넣지 않는 것:
    # CHZZK_ENABLED(옛 한 채널 경로 — 받기와 같이 켜면 부팅 거부) ·
    # CHAT_SYNC_OFFSET_MS(서버 기본 3900).
    "chat-collector" = merge(
      try(local.runtime.app_env["chat-collector"], {}),
      local.db_env["chat-collector"],
      {
        # 방송 편지로 세션을 연다(운영의 시작 경로). 켜면 AUTH_BASE_URL ·
        # INTERNAL_API_TOKEN(런타임 비밀)이 비면 부팅이 거부된다.
        BROADCAST_INTAKE_ENABLED = "true"
        AUTH_BASE_URL            = local.auth_base_url
        # 재부착 · 중계 · 방송 정보가 부르는 clip 내부 창구.
        CLIP_BASE_URL          = local.clip_base_url
        CHAT_REATTACH_ENABLED  = tostring(var.chat_collector_features.reattach)
        CHAT_RELAY_ENABLED     = tostring(var.chat_collector_features.relay)
        BROADCAST_INFO_ENABLED = tostring(var.chat_collector_features.liveinfo)
        CHAT_DETACH_ENABLED    = tostring(var.chat_collector_features.detach)
        SOOP_WATCH_ENABLED     = tostring(var.chat_collector_features.soop_watch)
        SOOP_CHAT_ENABLED      = tostring(var.chat_collector_features.soop_chat)
      },
    )
    "chat-detector" = merge(
      try(local.runtime.app_env["chat-detector"], {}),
      local.db_env["chat-detector"],
      {
        CLIP_BASE_URL      = local.clip_base_url
        COLLECTOR_BASE_URL = local.collector_base_url
      },
    )
  }

  service_secrets = {
    next = try(local.runtime.app_secret_arns.next, {})
    auth = try(local.runtime.app_secret_arns.auth, {})
    clip = merge(
      try(local.runtime.app_secret_arns.clip, {}),
      { CLOUDFRONT_PRIVATE_KEY_PEM = local.secrets.signing_key_secret_arn },
    )
    "chat-collector" = try(local.runtime.app_secret_arns["chat-collector"], {})
    "chat-detector"  = try(local.runtime.app_secret_arns["chat-detector"], {})
  }

  common_egress = {
    aws_api = { port = 443, cidr_ipv4 = "0.0.0.0/0", description = "AWS APIs via NAT" }
    s3      = { port = 443, prefix_list_id = local.network.s3_prefix_list_id, description = "S3 gateway endpoint" }
  }

  egress_targets = {
    next = merge(local.common_egress, {
      auth = { port = var.services.auth.port, security_group_id = module.auth.sg_id, description = "Next rewrite to auth" }
      clip = { port = var.services.clip.port, security_group_id = module.clip.sg_id, description = "Next rewrite to clip" }
    })
    auth = merge(local.common_egress, {
      rds              = { port = 5432, security_group_id = local.runtime.rds_sg_id, description = "RDS" }
      clip             = { port = var.services.clip.port, security_group_id = module.clip.sg_id, description = "auth to clip purge" }
      "chat-collector" = { port = local.collector_port, security_group_id = module.chat_collector.sg_id, description = "auth to chat-collector purge" }
    })
    clip = merge(local.common_egress, {
      rds              = { port = 5432, security_group_id = local.runtime.rds_sg_id, description = "RDS" }
      auth             = { port = var.services.auth.port, security_group_id = module.auth.sg_id, description = "clip to auth resolve" }
      "chat-collector" = { port = local.collector_port, security_group_id = module.chat_collector.sg_id, description = "clip to chat-collector" }
    })
    # collector → auth(열쇠 조회 · 링크) · clip(재부착 · 중계 · 방송 정보).
    # 치지직 · SOOP API · 채팅 소켓 · SQS 는 aws_api(443 → NAT)로 나간다.
    "chat-collector" = merge(local.common_egress, {
      rds  = { port = 5432, security_group_id = local.runtime.rds_sg_id, description = "RDS" }
      auth = { port = var.services.auth.port, security_group_id = module.auth.sg_id, description = "chat-collector to auth" }
      clip = { port = var.services.clip.port, security_group_id = module.clip.sg_id, description = "chat-collector to clip" }
    })
    # detector → clip(카드) · collector(채팅 읽기).
    "chat-detector" = merge(local.common_egress, {
      rds              = { port = 5432, security_group_id = local.runtime.rds_sg_id, description = "RDS" }
      clip             = { port = var.services.clip.port, security_group_id = module.clip.sg_id, description = "chat-detector to clip" }
      "chat-collector" = { port = local.collector_port, security_group_id = module.chat_collector.sg_id, description = "chat-detector to chat-collector" }
    })
  }
}

resource "aws_ecs_cluster" "this" {
  name = local.name_prefix
}

# 공개 ALB 대상 그룹 ARN 을 리스너 · 리스너 규칙이 다 생긴 뒤에 내보내는 다리.
# 리스너 규칙에 안 붙은 대상 그룹이면 ECS CreateService 가 「does not have an
# associated load balancer」 로 거부한다. 서비스가 이 출력으로 대상 그룹을 받아
# 규칙을 기다린다. 모듈 호출의 depends_on 은 쓰지 않는다 — 모듈 안 data 읽기가
# apply 로 밀려, ALB 만 바뀌어도 태스크 정의가 교체된다.
resource "terraform_data" "public_routed_target_groups" {
  input = module.public_alb.target_group_arns

  depends_on = [module.public_alb]
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = local.discovery_domain
  description = "prod ECS services"
  vpc         = local.network.vpc_id
}

module "next" {
  source = "../../modules/ecs_service"

  name_prefix            = local.name_prefix
  name                   = "next"
  iam_path               = local.iam_path
  cluster_arn            = aws_ecs_cluster.this.arn
  vpc_id                 = local.network.vpc_id
  subnet_ids             = local.network.private_subnet_ids
  image                  = local.service_images.next
  cpu                    = var.services.next.cpu
  memory                 = var.services.next.memory
  port                   = var.services.next.port
  health_check_grace     = var.services.next.health_check_grace
  log_retention_days     = local.log_retention_days
  env                    = local.service_env.next
  secrets                = local.service_secrets.next
  target_group_arns      = local.service_target_group_arns.next
  discovery_namespace_id = aws_service_discovery_private_dns_namespace.this.id
  egress_targets         = local.egress_targets.next
  task_role_statements   = local.task_role_statements.next
}

module "auth" {
  source = "../../modules/ecs_service"

  name_prefix            = local.name_prefix
  name                   = "auth"
  iam_path               = local.iam_path
  cluster_arn            = aws_ecs_cluster.this.arn
  vpc_id                 = local.network.vpc_id
  subnet_ids             = local.network.private_subnet_ids
  image                  = local.service_images.auth
  cpu                    = var.services.auth.cpu
  memory                 = var.services.auth.memory
  port                   = var.services.auth.port
  health_check_grace     = var.services.auth.health_check_grace
  log_retention_days     = local.log_retention_days
  env                    = local.service_env.auth
  secrets                = local.service_secrets.auth
  target_group_arns      = local.service_target_group_arns.auth
  discovery_namespace_id = aws_service_discovery_private_dns_namespace.this.id
  egress_targets         = local.egress_targets.auth
  task_role_statements   = local.task_role_statements.auth

  depends_on = [terraform_data.runtime_contract]
}

module "clip" {
  source = "../../modules/ecs_service"

  name_prefix            = local.name_prefix
  name                   = "clip"
  iam_path               = local.iam_path
  cluster_arn            = aws_ecs_cluster.this.arn
  vpc_id                 = local.network.vpc_id
  subnet_ids             = local.network.private_subnet_ids
  image                  = local.service_images.clip
  cpu                    = var.services.clip.cpu
  memory                 = var.services.clip.memory
  port                   = var.services.clip.port
  health_check_grace     = var.services.clip.health_check_grace
  log_retention_days     = local.log_retention_days
  env                    = local.service_env.clip
  secrets                = local.service_secrets.clip
  target_group_arns      = local.service_target_group_arns.clip
  discovery_namespace_id = aws_service_discovery_private_dns_namespace.this.id
  egress_targets         = local.egress_targets.clip
  task_role_statements   = local.task_role_statements.clip

  depends_on = [terraform_data.runtime_contract]
}

module "chat_collector" {
  source = "../../modules/ecs_service"

  name_prefix        = local.name_prefix
  name               = "chat-collector"
  iam_path           = local.iam_path
  cluster_arn        = aws_ecs_cluster.this.arn
  vpc_id             = local.network.vpc_id
  subnet_ids         = local.network.private_subnet_ids
  image              = local.service_images["chat-collector"]
  cpu                = var.chat_services["chat-collector"].cpu
  memory             = var.chat_services["chat-collector"].memory
  port               = var.chat_services["chat-collector"].port
  log_retention_days = local.log_retention_days
  env                = local.service_env["chat-collector"]
  secrets            = local.service_secrets["chat-collector"]
  # 대상 그룹이 없어 유예는 쓰이지 않는다(ECS API 가 대상 그룹 없이 받지 않음).
  target_group_arns      = []
  health_check_grace     = 0
  discovery_namespace_id = aws_service_discovery_private_dns_namespace.this.id
  egress_targets         = local.egress_targets["chat-collector"]
  task_role_statements   = local.task_role_statements["chat-collector"]

  depends_on = [terraform_data.runtime_contract]
}

# collector 와 순서를 두지 않는다(depends_on = [module.chat_collector] 없음) —
# 모듈 depends_on 은 collector 가 바뀔 때마다 이 모듈 안 data 읽기를 apply 로
# 밀어 태스크 정의를 새 판으로 바꾼다(위 public_routed_target_groups 주석).
# 서비스 생성은 안정 상태를 기다리지 않아 순서를 둬도 collector Flyway 가 먼저
# 끝난다는 보장이 되지 않는다(POK-306 Q5).
module "chat_detector" {
  source = "../../modules/ecs_service"

  name_prefix            = local.name_prefix
  name                   = "chat-detector"
  iam_path               = local.iam_path
  cluster_arn            = aws_ecs_cluster.this.arn
  vpc_id                 = local.network.vpc_id
  subnet_ids             = local.network.private_subnet_ids
  image                  = local.service_images["chat-detector"]
  cpu                    = var.chat_services["chat-detector"].cpu
  memory                 = var.chat_services["chat-detector"].memory
  port                   = var.chat_services["chat-detector"].port
  log_retention_days     = local.log_retention_days
  env                    = local.service_env["chat-detector"]
  secrets                = local.service_secrets["chat-detector"]
  target_group_arns      = []
  health_check_grace     = 0
  discovery_namespace_id = aws_service_discovery_private_dns_namespace.this.id
  egress_targets         = local.egress_targets["chat-detector"]
  task_role_statements   = local.task_role_statements["chat-detector"]

  depends_on = [terraform_data.runtime_contract]
}

resource "aws_vpc_security_group_ingress_rule" "service_from_public_alb" {
  for_each = var.services

  security_group_id            = local.service_sg_ids[each.key]
  description                  = "public ALB"
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  referenced_security_group_id = module.public_alb.sg_id
}

resource "aws_vpc_security_group_ingress_rule" "from_next" {
  for_each = toset(["auth", "clip"])

  security_group_id            = local.service_sg_ids[each.key]
  description                  = "Next rewrite"
  ip_protocol                  = "tcp"
  from_port                    = var.services[each.key].port
  to_port                      = var.services[each.key].port
  referenced_security_group_id = module.next.sg_id
}

# clip → auth 내부 resolve(DelegationResolveClient — 쿠키 발급 · SSE 자격
# 판정)의 짝. 없으면 clip 은 healthy 인데 자격 판정이 시간 초과 → 503 이다.
resource "aws_vpc_security_group_ingress_rule" "auth_from_clip" {
  security_group_id            = module.auth.sg_id
  description                  = "clip to auth resolve"
  ip_protocol                  = "tcp"
  from_port                    = var.services.auth.port
  to_port                      = var.services.auth.port
  referenced_security_group_id = module.clip.sg_id
}

# auth → clip 탈퇴 정리(WithdrawalPurge)의 짝.
resource "aws_vpc_security_group_ingress_rule" "clip_from_auth" {
  security_group_id            = module.clip.sg_id
  description                  = "auth to clip purge"
  ip_protocol                  = "tcp"
  from_port                    = var.services.clip.port
  to_port                      = var.services.clip.port
  referenced_security_group_id = module.auth.sg_id
}

# chat-collector 8083 ← auth(탈퇴 정리 — COLLECTOR_BASE_URL) · clip(채팅 문 셋) ·
# chat-detector(채팅 읽기)의 짝. 상대 이그레스는 egress_targets 에 있다.
resource "aws_vpc_security_group_ingress_rule" "chat_collector_from_service" {
  for_each = {
    auth            = module.auth.sg_id
    clip            = module.clip.sg_id
    "chat-detector" = module.chat_detector.sg_id
  }

  security_group_id            = module.chat_collector.sg_id
  description                  = "${each.key} to chat-collector"
  ip_protocol                  = "tcp"
  from_port                    = local.collector_port
  to_port                      = local.collector_port
  referenced_security_group_id = each.value
}

# chat-collector → auth(방송 편지의 열쇠 조회 · 링크 — AUTH_BASE_URL)의 짝.
resource "aws_vpc_security_group_ingress_rule" "auth_from_chat_collector" {
  security_group_id            = module.auth.sg_id
  description                  = "chat-collector to auth"
  ip_protocol                  = "tcp"
  from_port                    = var.services.auth.port
  to_port                      = var.services.auth.port
  referenced_security_group_id = module.chat_collector.sg_id
}

# chat-collector(재부착 · 중계 · 방송 정보) · chat-detector(카드) → clip 의 짝.
resource "aws_vpc_security_group_ingress_rule" "clip_from_chat" {
  for_each = {
    "chat-collector" = module.chat_collector.sg_id
    "chat-detector"  = module.chat_detector.sg_id
  }

  security_group_id            = module.clip.sg_id
  description                  = "${each.key} to clip"
  ip_protocol                  = "tcp"
  from_port                    = var.services.clip.port
  to_port                      = var.services.clip.port
  referenced_security_group_id = each.value
}

# RDS SG 는 런타임 1단이 만들고, 서비스 SG 를 아는 이 루트가 규칙을 갖는다
# (설계 3-1). Media ↔ RDS 는 media 루트 몫이다. 키는 DB 를 쓰는 서비스 넷
# (chat 두 키를 더해도 auth · clip 규칙은 그대로다).
resource "aws_vpc_security_group_ingress_rule" "rds_from_service" {
  for_each = local.db_client_sg_ids

  security_group_id            = local.runtime.rds_sg_id
  description                  = "${each.key} to RDS"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = each.value

  depends_on = [terraform_data.runtime_contract]
}
