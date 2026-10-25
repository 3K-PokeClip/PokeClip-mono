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

  # 데이터 보호로 가릴 이름 — 필드 종류(field_type)마다 항목 하나. 빈 목록은
  # 항목째 뺀다 — SINGLE_HEADER · SINGLE_QUERY_ARGUMENT 는 비어 있지 않은
  # FieldKeys 가 필요하고, 그 종류의 모든 키를 가리는 선택지는 없다(AWS WAF
  # API 「FieldToProtect」).
  protected_fields = {
    for field_type, keys in {
      SINGLE_HEADER         = var.protected_headers
      SINGLE_QUERY_ARGUMENT = var.protected_query_args
    } : field_type => keys if length(keys) > 0
  }
}

resource "aws_wafv2_web_acl" "this" {
  name  = var.name
  scope = var.scope

  default_action {
    allow {}
  }

  # 웹 ACL 수준 데이터 보호는 요청 샘플 · 로그 · Security Lake 에 다 걸리고,
  # 로깅 설정의 redacted_fields 는 로그에만 걸린다(샘플은 원문 — AWS WAF API
  # 「VisibilityConfig」). 쿠키는 SINGLE_COOKIE 가 아니라 헤더 cookie 로 막는다
  # — 쿠키 값은 cookie 헤더를 단일 헤더로 보호할 때만 가려진다(AWS WAF 개발자
  # 안내서 「Data protection limitations」). 질의 인자를 하나라도 가리면 로그의
  # 규칙 일치 · 속도 규칙 세부에서는 질의 문자열 전체가 REDACTED 가 된다(같은
  # 절).
  dynamic "data_protection_config" {
    for_each = length(local.protected_fields) == 0 ? [] : [local.protected_fields]

    content {
      dynamic "data_protection" {
        for_each = data_protection_config.value

        content {
          action = "SUBSTITUTION"

          field {
            field_type = data_protection.key
            field_keys = data_protection.value
          }
        }
      }
    }
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
