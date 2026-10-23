# 공개 ALB(app · api — 설계 4-2 · 4-3) · REGIONAL WAF · 내부 ALB(media —
# CloudFront VPC origin 뒤, 4-4 · 4-5) · SRT NLB(4-6). 두 ALB 리스너의 기본
# 동작은 고정 404 다(alb 모듈 — 규칙에 걸린 요청만 대상에 닿는다).
#
# 이 루트에 없는 규칙: 내부 ALB 인그레스 ← CloudFront VPC origin(edge) · 내부
# ALB · NLB 이그레스 → Media 와 Media 대상 등록(media — 5-5).

locals {
  # next 의 keep-alive 65초 · clip SSE 하트비트 20초보다 짧다.
  alb_idle_timeout = 60

  # 공개 ALB 는 잘못된 헤더(HTTP 헤더 이름 규칙 밖)를 버린다 — 요청 밀수 방어.
  public_alb_drop_invalid_header_fields = true

  # web/next.config.ts 에서 AUTH_API_URL 로 가는 접두사(7 · 9 · 14 · 18 · 19 ·
  # 24 · 26 · 29 · 30행). 그 파일에 줄이 늘면 여기도 늘린다.
  auth_path_prefixes = [
    "/api/auth",
    "/api/stream-keys",
    "/api/chzzk-link",
    "/api/editor-delegations",
    "/api/editor-invitations",
    "/api/youtube-link",
    "/api/soop-link",
    "/api/streamers",
    "/api/profile-photos",
  ]

  service_target_group = {
    for name, service in var.services : name => {
      port        = service.port
      protocol    = "HTTP"
      target_type = "ip"
      health_check = {
        port = "traffic-port"
        path = service.health_check_path
      }
    }
  }

  # clip-sse 는 같은 clip 서비스다 — 긴 SSE 연결이 clip 그룹의 응답 시간 지표를
  # 흐리지 않게 뗀다(설계 4-3).
  public_target_groups = merge(local.service_target_group, {
    clip-sse = local.service_target_group.clip
  })

  # auth 는 접두사마다 규칙 하나로 나뉜다(priority + 0 … + 8 — 조건 값 한도,
  # 계획 P1). clip-sse 는 clip 보다 앞이어야 SSE 가 clip-sse 로 간다.
  public_listener_rules = {
    auth = {
      priority      = 100
      target_group  = "auth"
      host_headers  = [var.api_host]
      path_prefixes = local.auth_path_prefixes
      path_patterns = []
      path_rewrite  = null
    }
    clip-sse = {
      priority      = 200
      target_group  = "clip-sse"
      host_headers  = [var.api_host]
      path_prefixes = []
      path_patterns = ["/api/clip/broadcasts/*/events"]
      path_rewrite  = null
    }
    clip = {
      priority      = 300
      target_group  = "clip"
      host_headers  = [var.api_host]
      path_prefixes = []
      path_patterns = ["/api/clip/*"]
      path_rewrite  = null
    }
    next = {
      priority      = 400
      target_group  = "next"
      host_headers  = [var.app_host]
      path_prefixes = []
      path_patterns = []
      path_rewrite  = null
    }
  }

  # Media 상자 nginx 포트 · 헬스 경로의 정본 — 출력으로 내보내 media 루트가
  # 같은 값으로 상자를 띄운다.
  media_proxy_ports = tomap(var.proxy_ports)
  media_health_path = var.media_health_path

  # 헬스는 프록시 헬스 포트 · 경로다(traffic-port 아님) — 제어면 서버가 없어도
  # control 이 healthy 다(설계 3-3-1).
  internal_target_groups = {
    for name in ["live", "control", "playback"] : name => {
      port        = local.media_proxy_ports[name]
      protocol    = "HTTP"
      target_type = "instance"
      health_check = {
        port = tostring(local.media_proxy_ports["health"])
        path = local.media_health_path
      }
    }
  }

  internal_listener_rules = {
    live = {
      priority      = 100
      target_group  = "live"
      path_patterns = ["/live/*"]
      path_rewrite  = { regex = "^/live/(.*)$", replace = "/$1" }
    }
    rewind = {
      priority      = 200
      target_group  = "control"
      path_patterns = ["/api/streams/*/rewind"]
      path_rewrite  = null
    }
    ingest = {
      priority      = 300
      target_group  = "control"
      path_patterns = ["/api/ingest/*"]
      path_rewrite  = null
    }
    playback = {
      priority      = 400
      target_group  = "playback"
      path_patterns = ["/playback/*"]
      path_rewrite  = { regex = "^/playback/(.*)$", replace = "/$1" }
    }
  }

  nlb_subnet_id   = local.network.media_public_subnet_id
  nlb_health_port = local.media_proxy_ports["health"]
}

module "waf" {
  source = "../../modules/waf_acl"

  name                = "pokeclip-prod-regional"
  scope               = "REGIONAL"
  managed_rule_groups = var.waf_managed_rule_groups
  rate_limit          = var.waf_rate_limit
}

module "public_alb" {
  source = "../../modules/alb"

  name            = var.public_alb_name
  internal        = false
  vpc_id          = local.network.vpc_id
  subnet_ids      = local.network.public_subnet_ids
  certificate_arn = local.certs.cert_arn_apne2
  idle_timeout    = local.alb_idle_timeout
  target_groups   = local.public_target_groups
  listener_rules  = local.public_listener_rules

  drop_invalid_header_fields = local.public_alb_drop_invalid_header_fields
}

resource "aws_wafv2_web_acl_association" "public" {
  resource_arn = module.public_alb.alb_arn
  web_acl_arn  = module.waf.web_acl_arn
}

module "internal_alb" {
  source = "../../modules/alb"

  name            = var.internal_alb_name
  internal        = true
  vpc_id          = local.network.vpc_id
  subnet_ids      = local.network.private_subnet_ids
  certificate_arn = local.certs.cert_arn_apne2
  idle_timeout    = local.alb_idle_timeout
  target_groups   = local.internal_target_groups
  listener_rules  = local.internal_listener_rules
}

module "nlb" {
  source = "../../modules/media_ingest"

  name                 = "pokeclip-prod"
  vpc_id               = local.network.vpc_id
  subnet_id            = local.nlb_subnet_id
  health_port          = local.nlb_health_port
  ingest_allowed_cidrs = var.ingest_allowed_cidrs
}

resource "aws_vpc_security_group_ingress_rule" "public_alb_https" {
  security_group_id = module.public_alb.sg_id
  description       = "HTTPS from internet"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# 서비스 포트 = 헬스 포트(traffic-port)라 서비스마다 규칙 하나다.
resource "aws_vpc_security_group_egress_rule" "public_alb_to_service" {
  for_each = var.services

  security_group_id            = module.public_alb.sg_id
  description                  = "ALB to ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  referenced_security_group_id = local.service_sg_ids[each.key]
}
