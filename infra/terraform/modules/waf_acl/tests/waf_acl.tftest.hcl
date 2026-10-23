# WAF Web ACL 모듈(설계 3-3 의 waf_acl · 계획 M8 c1)을 mock provider 로
# 단언한다 — 범위(scope) · 관리 규칙 그룹 이름(AWS 제공 · 그룹의 동작을 그대로
# 씀) · 속도 제한 값(IP 마다 5분 창 · 넘으면 차단) · 기본 동작 허용 · 출력.
# 이름은 가짜 값이다.

mock_provider "aws" {}

variables {
  name  = "example-regional"
  scope = "REGIONAL"
  managed_rule_groups = [
    "AWSManagedRulesCommonRuleSet",
    "AWSManagedRulesKnownBadInputsRuleSet",
  ]
  rate_limit = 1000
}

run "creates_regional_acl_allowing_by_default" {
  command = plan

  assert {
    condition     = aws_wafv2_web_acl.this.scope == "REGIONAL"
    error_message = "Web ACL 범위가 입력 scope(REGIONAL)가 아니다."
  }

  assert {
    condition     = aws_wafv2_web_acl.this.name == "example-regional"
    error_message = "Web ACL 이름이 입력 name 이 아니다."
  }

  # 규칙에 걸리지 않은 요청은 통과시킨다 — 차단은 규칙만 한다.
  assert {
    condition = (
      length(aws_wafv2_web_acl.this.default_action[0].allow) == 1 &&
      length(aws_wafv2_web_acl.this.default_action[0].block) == 0
    )
    error_message = "Web ACL 기본 동작이 허용(allow)이 아니다."
  }

  assert {
    condition = (
      aws_wafv2_web_acl.this.visibility_config[0].cloudwatch_metrics_enabled &&
      aws_wafv2_web_acl.this.visibility_config[0].sampled_requests_enabled &&
      aws_wafv2_web_acl.this.visibility_config[0].metric_name == "example-regional"
    )
    error_message = "Web ACL 지표 · 표본 요청이 켜져 있지 않거나 지표 이름이 name 이 아니다."
  }
}

run "uses_cloudfront_scope_when_given" {
  command = plan

  variables {
    scope = "CLOUDFRONT"
  }

  assert {
    condition     = aws_wafv2_web_acl.this.scope == "CLOUDFRONT"
    error_message = "Web ACL 범위가 입력 scope(CLOUDFRONT)가 아니다."
  }
}

run "adds_managed_rule_groups_by_name" {
  command = plan

  assert {
    condition = toset([
      for rule in aws_wafv2_web_acl.this.rule :
      rule.statement[0].managed_rule_group_statement[0].name
      if length(rule.statement[0].managed_rule_group_statement) > 0
    ]) == toset(var.managed_rule_groups)
    error_message = "관리 규칙 그룹 이름 집합이 입력 managed_rule_groups 와 다르다."
  }

  assert {
    condition = alltrue([
      for rule in aws_wafv2_web_acl.this.rule :
      rule.statement[0].managed_rule_group_statement[0].vendor_name == "AWS"
      if length(rule.statement[0].managed_rule_group_statement) > 0
    ])
    error_message = "관리 규칙 그룹의 공급자가 AWS 가 아니다."
  }

  # 관리 규칙 그룹은 그룹이 정한 동작(차단)을 그대로 쓴다 — count 로 낮추지
  # 않는다.
  assert {
    condition = alltrue([
      for rule in aws_wafv2_web_acl.this.rule :
      length(rule.override_action[0].none) == 1 && length(rule.override_action[0].count) == 0
      if length(rule.statement[0].managed_rule_group_statement) > 0
    ])
    error_message = "관리 규칙 그룹의 override_action 이 none 이 아니다."
  }
}

run "blocks_requests_over_rate_limit_per_ip" {
  command = plan

  assert {
    condition = [
      for rule in aws_wafv2_web_acl.this.rule :
      rule.statement[0].rate_based_statement[0].limit
      if length(rule.statement[0].rate_based_statement) > 0
    ] == [1000]
    error_message = "속도 제한 규칙이 하나가 아니거나 한도가 입력 rate_limit(1000)이 아니다."
  }

  assert {
    condition = alltrue([
      for rule in aws_wafv2_web_acl.this.rule :
      (
        rule.statement[0].rate_based_statement[0].aggregate_key_type == "IP" &&
        rule.statement[0].rate_based_statement[0].evaluation_window_sec == 300 &&
        length(rule.action[0].block) == 1
      )
      if length(rule.statement[0].rate_based_statement) > 0
    ])
    error_message = "속도 제한 규칙이 IP 마다 5분 창으로 세어 차단하지 않는다."
  }
}

run "gives_every_rule_distinct_priority_and_metrics" {
  command = plan

  assert {
    condition     = length(aws_wafv2_web_acl.this.rule) == 3
    error_message = "규칙 수가 관리 규칙 그룹 2 + 속도 제한 1 이 아니다."
  }

  assert {
    condition = length(distinct([
      for rule in aws_wafv2_web_acl.this.rule : rule.priority
    ])) == length(aws_wafv2_web_acl.this.rule)
    error_message = "규칙 우선순위가 겹친다."
  }

  assert {
    condition = alltrue([
      for rule in aws_wafv2_web_acl.this.rule :
      rule.visibility_config[0].cloudwatch_metrics_enabled && rule.visibility_config[0].sampled_requests_enabled
    ])
    error_message = "규칙의 지표 · 표본 요청이 켜져 있지 않다."
  }
}

run "outputs_web_acl_arn" {
  command = apply

  assert {
    condition     = output.web_acl_arn == aws_wafv2_web_acl.this.arn
    error_message = "출력 web_acl_arn 이 Web ACL ARN 이 아니다."
  }
}

run "rejects_unknown_scope" {
  command = plan

  variables {
    scope = "GLOBAL"
  }

  expect_failures = [var.scope]
}

run "rejects_empty_managed_rule_groups" {
  command = plan

  variables {
    managed_rule_groups = []
  }

  expect_failures = [var.managed_rule_groups]
}

run "rejects_duplicate_managed_rule_groups" {
  command = plan

  variables {
    managed_rule_groups = ["AWSManagedRulesCommonRuleSet", "AWSManagedRulesCommonRuleSet"]
  }

  expect_failures = [var.managed_rule_groups]
}

# WAF 속도 제한 한도는 10 부터 2,000,000,000 까지다(AWS WAF API
# RateBasedStatement.Limit).
run "rejects_rate_limit_below_ten" {
  command = plan

  variables {
    rate_limit = 9
  }

  expect_failures = [var.rate_limit]
}

run "rejects_fractional_rate_limit" {
  command = plan

  variables {
    rate_limit = 100.5
  }

  expect_failures = [var.rate_limit]
}
