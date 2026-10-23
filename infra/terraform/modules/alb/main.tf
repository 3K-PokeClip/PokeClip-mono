# ALB(설계 3-3 의 alb). 공개 ALB(next · auth · clip — ip 대상)와 내부
# ALB(Media — instance 대상 · CloudFront VPC origin 뒤)를 같은 모듈로 만든다.
#
# 443 리스너의 기본 동작은 고정 404 다(설계 3-3 「기본 동작은 전부 거절」) —
# 규칙에 걸린 요청만 대상에 닿는다. 입력으로 바꿀 수 없다.
#
# SG 는 모듈이 만들고 규칙은 하나도 만들지 않는다. 인그레스 · 이그레스는
# 호출하는 루트가 별도 자원으로 붙인다(guard R3 · 설계 5-5) — 내부 ALB →
# Media 이그레스는 media 루트, CloudFront VPC origin → 내부 ALB 인그레스는
# edge 루트가 갖는다.

locals {
  # 규칙마다 경로 값 목록 — 접두사는 「/x」 · 「/x/*」 두 값으로 편다.
  rule_paths = {
    for key, rule in var.listener_rules :
    key => concat(flatten([for prefix in rule.path_prefixes : [prefix, "${prefix}/*"]]), rule.path_patterns)
  }

  # 규칙 <키>/<i> — 경로 값을 max_path_values_per_rule 개씩 나눈 조각. 경로가
  # 없는 규칙(host 만)은 조각 하나다.
  split_rules = merge([
    for key, rule in var.listener_rules : {
      for index, paths in(length(local.rule_paths[key]) == 0 ? [[]] : chunklist(local.rule_paths[key], var.max_path_values_per_rule)) :
      "${key}/${index}" => {
        priority     = rule.priority + index
        target_group = rule.target_group
        host_headers = rule.host_headers
        paths        = paths
        path_rewrite = rule.path_rewrite
      }
    }
  ]...)

  split_priorities = [for rule in values(local.split_rules) : rule.priority]
}

resource "aws_security_group" "this" {
  name        = "${var.name}-alb"
  description = "ALB ${var.name}"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${var.name}-alb"
  }
}

resource "aws_lb" "this" {
  name               = var.name
  load_balancer_type = "application"
  internal           = var.internal
  subnets            = var.subnet_ids
  security_groups    = [aws_security_group.this.id]
  idle_timeout       = var.idle_timeout
}

resource "aws_lb_target_group" "this" {
  for_each = var.target_groups

  name                 = "${var.name}-${each.key}"
  vpc_id               = var.vpc_id
  port                 = each.value.port
  protocol             = each.value.protocol
  target_type          = each.value.target_type
  deregistration_delay = 30

  health_check {
    enabled  = true
    protocol = each.value.protocol
    port     = each.value.health_check.port
    path     = each.value.health_check.path
    matcher  = each.value.health_check.matcher
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "this" {
  for_each = local.split_rules

  listener_arn = aws_lb_listener.https.arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.value.target_group].arn
  }

  dynamic "condition" {
    for_each = length(each.value.host_headers) > 0 ? [each.value.host_headers] : []

    content {
      host_header {
        values = condition.value
      }
    }
  }

  dynamic "condition" {
    for_each = length(each.value.paths) > 0 ? [each.value.paths] : []

    content {
      path_pattern {
        values = condition.value
      }
    }
  }

  dynamic "transform" {
    for_each = each.value.path_rewrite == null ? [] : [each.value.path_rewrite]

    content {
      type = "url-rewrite"

      url_rewrite_config {
        rewrite {
          regex   = transform.value.regex
          replace = transform.value.replace
        }
      }
    }
  }

  lifecycle {
    # 조각으로 늘어난 우선순위가 다른 규칙과 겹치거나 ALB 범위(1 ~ 50000)를
    # 벗어나면 apply 전에 멈춘다.
    precondition {
      condition = (
        length(distinct(local.split_priorities)) == length(local.split_priorities) &&
        alltrue([for priority in local.split_priorities : priority >= 1 && priority <= 50000])
      )
      error_message = "listener_rules 의 조각 우선순위(priority + i)가 겹치거나 1 ~ 50000 밖이다."
    }
  }
}
