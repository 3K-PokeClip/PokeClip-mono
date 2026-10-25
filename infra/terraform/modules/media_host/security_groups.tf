# Media SG 와 그 짝 규칙(설계 5-5). 규칙은 모두 별도 자원이다(guard R3).
#
# - 인그레스: SRT ← NLB · 프록시 셋 ← 내부 ALB · 헬스 ← 둘. MediaMTX 루프백
#   포트(8888 · 9996 · 9997)는 열지 않는다 — 입력 검증이 프록시 포트와 겹치지
#   않게 막는다. 공개 ALB SG 는 출처로 받지 않는다.
# - 이그레스: 443 → S3 prefix list · 443 → 0.0.0.0/0(NAT — AWS API · ECR ·
#   compose 릴리스) · 5432 → RDS SG · auth 포트 → auth SG(응답기의 4C resolve).
# - 짝: RDS 인그레스 ← Media 5432(kty 21차 — 이그레스와 한 apply), auth
#   인그레스 ← Media(kty 결정 2026-10-25 (나) — 같은 꼴), 로드밸런서 SG
#   이그레스 → Media(platform 이 만들면 media_sg_id 가 필요해 순환한다).

locals {
  media_ingress_rules = {
    srt        = { protocol = "udp", port = var.srt_port, source = var.nlb_sg_id }
    live       = { protocol = "tcp", port = var.proxy_ports.live, source = var.internal_alb_sg_id }
    playback   = { protocol = "tcp", port = var.proxy_ports.playback, source = var.internal_alb_sg_id }
    control    = { protocol = "tcp", port = var.proxy_ports.control, source = var.internal_alb_sg_id }
    health_alb = { protocol = "tcp", port = var.proxy_ports.health, source = var.internal_alb_sg_id }
    health_nlb = { protocol = "tcp", port = var.proxy_ports.health, source = var.nlb_sg_id }
  }

  # 로드밸런서 SG 에서 Media SG 로 나가는 규칙 — 인그레스와 같은 포트 · 같은 짝.
  load_balancer_egress_rules = {
    alb_live     = { sg = var.internal_alb_sg_id, protocol = "tcp", port = var.proxy_ports.live }
    alb_playback = { sg = var.internal_alb_sg_id, protocol = "tcp", port = var.proxy_ports.playback }
    alb_control  = { sg = var.internal_alb_sg_id, protocol = "tcp", port = var.proxy_ports.control }
    alb_health   = { sg = var.internal_alb_sg_id, protocol = "tcp", port = var.proxy_ports.health }
    nlb_srt      = { sg = var.nlb_sg_id, protocol = "udp", port = var.srt_port }
    nlb_health   = { sg = var.nlb_sg_id, protocol = "tcp", port = var.proxy_ports.health }
  }
}

resource "aws_security_group" "media" {
  name        = "${var.name}-media"
  description = "Media host - ingress from NLB and internal ALB only"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.name}-media"
  }
}

resource "aws_vpc_security_group_ingress_rule" "media" {
  for_each = local.media_ingress_rules

  security_group_id            = aws_security_group.media.id
  ip_protocol                  = each.value.protocol
  from_port                    = each.value.port
  to_port                      = each.value.port
  referenced_security_group_id = each.value.source
  description                  = "media ${each.key}"
}

resource "aws_vpc_security_group_egress_rule" "media" {
  for_each = {
    https_s3     = { prefix_list_id = var.s3_prefix_list_id, cidr_ipv4 = null, sg = null, port = 443 }
    https_any    = { prefix_list_id = null, cidr_ipv4 = "0.0.0.0/0", sg = null, port = 443 }
    postgres     = { prefix_list_id = null, cidr_ipv4 = null, sg = var.rds_sg_id, port = 5432 }
    auth_resolve = { prefix_list_id = null, cidr_ipv4 = null, sg = var.auth_sg_id, port = var.auth_port }
  }

  security_group_id            = aws_security_group.media.id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  prefix_list_id               = each.value.prefix_list_id
  cidr_ipv4                    = each.value.cidr_ipv4
  referenced_security_group_id = each.value.sg
  description                  = "media ${each.key}"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_media" {
  security_group_id            = var.rds_sg_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.media.id
  description                  = "postgres from media host"
}

resource "aws_vpc_security_group_ingress_rule" "auth_from_media" {
  security_group_id            = var.auth_sg_id
  ip_protocol                  = "tcp"
  from_port                    = var.auth_port
  to_port                      = var.auth_port
  referenced_security_group_id = aws_security_group.media.id
  description                  = "stream key resolve from media host"
}

resource "aws_vpc_security_group_egress_rule" "load_balancer" {
  for_each = local.load_balancer_egress_rules

  security_group_id            = each.value.sg
  ip_protocol                  = each.value.protocol
  from_port                    = each.value.port
  to_port                      = each.value.port
  referenced_security_group_id = aws_security_group.media.id
  description                  = "to media ${each.key}"
}
