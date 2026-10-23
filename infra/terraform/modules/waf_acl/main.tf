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

# 트래픽 로그 — 차단(BLOCK)된 요청만 CloudWatch Logs 로 보낸다. 로그 그룹 자원
# 정책(delivery.logs.amazonaws.com 이 쓰는 권한)은 로깅을 켤 때 AWS 가 만든다
# — 실행자에게 logs:PutResourcePolicy · DescribeResourcePolicies ·
# DescribeLogGroups 가 있으면(CloudWatch Logs 안내서 「Logs sent to
# CloudWatch Logs」).
resource "aws_cloudwatch_log_group" "this" {
  count = var.logging == null ? 0 : 1

  name              = var.logging.log_group_name
  retention_in_days = var.logging.retention_in_days
}

resource "aws_wafv2_web_acl_logging_configuration" "this" {
  count = var.logging == null ? 0 : 1

  resource_arn            = aws_wafv2_web_acl.this.arn
  log_destination_configs = [aws_cloudwatch_log_group.this[0].arn]

  dynamic "redacted_fields" {
    for_each = var.logging.redacted_headers

    content {
      single_header {
        name = redacted_fields.value
      }
    }
  }

  logging_filter {
    default_behavior = "DROP"

    filter {
      behavior    = "KEEP"
      requirement = "MEETS_ANY"

      condition {
        action_condition {
          action = "BLOCK"
        }
      }
    }
  }
}
