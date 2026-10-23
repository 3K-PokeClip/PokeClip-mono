# ECS Fargate 서비스 셋(next · auth · clip)과 클러스터 · Cloud Map · 서비스
# SG 인그레스 · RDS 인그레스 ← auth · clip(설계 3-1 의 ⑧ · 5-5).
#
# Next 는 rewrite 목적지를 빌드 때 굳힌다(web README 「컨테이너 이미지」) —
# 이미지 빌드 인자 AUTH_API_URL · CLIP_API_URL 은 아래 Cloud Map 이름
# (http://auth.pokeclip.internal:8082 · http://clip.pokeclip.internal:8081)이다.

locals {
  name_prefix        = "pokeclip-prod"
  iam_path           = "/pokeclip/prod/"
  log_retention_days = 30
  discovery_domain   = "pokeclip.internal"

  # chat-collector 는 POK-408 이 같은 네임스페이스에 등록한다(이름 · 포트 계약).
  collector_base_url = "http://chat-collector.${local.discovery_domain}:8083"

  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region

  service_images = {
    for name in keys(var.services) :
    name => "${data.terraform_remote_state.registry.outputs.repo_urls[name]}@${var.image_digests[name]}"
  }

  service_sg_ids = {
    next = module.next.sg_id
    auth = module.auth.sg_id
    clip = module.clip.sg_id
  }

  db_users = {
    for service in ["auth", "clip"] : service => local.runtime.app_db_users[service]
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
        FORWARD_HEADERS_STRATEGY     = "native"
        STREAM_KEY_SECRET_REF_PREFIX = var.stream_key_secret_prefix
        CORS_ALLOWED_ORIGINS         = var.cors_allowed_origins
        # 탈퇴 정리(withdrawal-purge) — 비면 부팅이 거부된다.
        CLIP_BASE_URL      = "http://clip.${local.discovery_domain}:${var.services.clip.port}"
        COLLECTOR_BASE_URL = local.collector_base_url
      },
      { for key, value in { SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES = var.auth_internal_proxies } : key => value if value != "" },
    )
    clip = merge(
      try(local.runtime.app_env.clip, {}),
      local.db_env.clip,
      {
        CLOUDFRONT_KEY_PAIR_ID = local.secrets.public_key_id
        MEDIA_BASE_URL         = var.media_base_url
        PLAYBACK_COOKIE_DOMAIN = var.playback_cookie_domain
        AUTH_BASE_URL          = "http://auth.${local.discovery_domain}:${var.services.auth.port}"
        CORS_ALLOWED_ORIGINS   = var.cors_allowed_origins
        # 서명 값 셋 중 하나라도 빠지면 부팅을 거부한다(POK-321).
        PLAYBACK_REQUIRED = "true"
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
      rds  = { port = 5432, security_group_id = local.runtime.rds_sg_id, description = "RDS" }
      clip = { port = var.services.clip.port, security_group_id = module.clip.sg_id, description = "auth to clip purge" }
    })
    clip = merge(local.common_egress, {
      rds  = { port = 5432, security_group_id = local.runtime.rds_sg_id, description = "RDS" }
      auth = { port = var.services.auth.port, security_group_id = module.auth.sg_id, description = "clip to auth resolve" }
    })
  }
}

resource "aws_ecs_cluster" "this" {
  name = local.name_prefix
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
  target_group_arns      = [module.public_alb.target_group_arns["next"]]
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
  target_group_arns      = [module.public_alb.target_group_arns["auth"]]
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
  target_group_arns      = [module.public_alb.target_group_arns["clip"], module.public_alb.target_group_arns["clip-sse"]]
  discovery_namespace_id = aws_service_discovery_private_dns_namespace.this.id
  egress_targets         = local.egress_targets.clip
  task_role_statements   = local.task_role_statements.clip

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

# auth → clip 탈퇴 정리(WithdrawalPurge)의 짝. auth → chat-collector 는 런타임
# 2단(POK-306) 몫이다.
resource "aws_vpc_security_group_ingress_rule" "clip_from_auth" {
  security_group_id            = module.clip.sg_id
  description                  = "auth to clip purge"
  ip_protocol                  = "tcp"
  from_port                    = var.services.clip.port
  to_port                      = var.services.clip.port
  referenced_security_group_id = module.auth.sg_id
}

# RDS SG 는 런타임 1단이 만들고, auth · clip SG 를 아는 이 루트가 규칙을
# 갖는다(설계 3-1). Media ↔ RDS 는 media 루트 몫이다.
resource "aws_vpc_security_group_ingress_rule" "rds_from_service" {
  for_each = toset(["auth", "clip"])

  security_group_id            = local.runtime.rds_sg_id
  description                  = "${each.key} to RDS"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = local.service_sg_ids[each.key]

  depends_on = [terraform_data.runtime_contract]
}
