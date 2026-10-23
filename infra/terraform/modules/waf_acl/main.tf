# WAF Web ACL(설계 3-3 의 waf_acl). REGIONAL 은 platform 이 공개 ALB 에,
# CLOUDFRONT 는 edge 가 배포에 붙인다 — 연결은 호출하는 루트가 한다.
#
# 규칙에 걸리지 않은 요청은 통과시킨다. 속도 제한(IP 마다 5분 창)을 먼저
# 보고, 그다음 AWS 관리 규칙 그룹을 입력 순서대로 본다. 관리 규칙 그룹은
# 그룹이 정한 동작을 그대로 쓴다(override_action = none).

locals {
  # 우선순위 0 은 속도 제한 — 관리 규칙 그룹은 1 부터.
  managed_rule_priorities = {
    for index, group in var.managed_rule_groups : group => index + 1
  }
}

resource "aws_wafv2_web_acl" "this" {
  name  = var.name
  scope = var.scope

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit"
    priority = 0

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.rate_limit
        aggregate_key_type    = "IP"
        evaluation_window_sec = 300
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      sampled_requests_enabled   = true
      metric_name                = "${var.name}-rate-limit"
    }
  }

  dynamic "rule" {
    for_each = local.managed_rule_priorities

    content {
      name     = rule.key
      priority = rule.value

      override_action {
        none {}
      }

      statement {
        managed_rule_group_statement {
          name        = rule.key
          vendor_name = "AWS"
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        sampled_requests_enabled   = true
        metric_name                = "${var.name}-${rule.key}"
      }
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    sampled_requests_enabled   = true
    metric_name                = var.name
  }
}
