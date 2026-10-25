# WAF Web ACL 모듈(설계 3-3 의 waf_acl · 계획 M8 c1)을 mock provider 로
# 단언한다 — 범위(scope) · 관리 규칙 그룹 이름(AWS 제공 · 그룹의 동작을 그대로
# 씀) · 속도 제한 값(IP 마다 5분 창 · 넘으면 차단) · 기본 동작 허용 · 출력 ·
# 트래픽 로그(기본 꺼짐 · 켜면 CloudWatch Logs 로 차단만 · 헤더 가림) · 웹 ACL
# 데이터 보호(기본 꺼짐 · 켜면 헤더 · 질의 인자 값을 REDACTED 로 치환).
# 이름 · 계정 ID(111122223333)는 가짜 값이다.

mock_provider "aws" {
  # apply 로 도는 run 에서 provider 가 ARN 꼴을 검사하는 참조에 쓸 값.
  mock_resource "aws_wafv2_web_acl" {
    defaults = {
      arn = "arn:aws:wafv2:ap-northeast-2:111122223333:regional/webacl/example-regional/00000000-0000-0000-0000-000000000000"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:ap-northeast-2:111122223333:log-group:aws-waf-logs-example"
    }
  }
}

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

# --- 트래픽 로그 ---

# 같은 모듈을 CLOUDFRONT WAF 도 쓴다 — logging 을 주지 않은 호출은 로그 자원이
# 생기지 않는다.
run "logs_nothing_by_default" {
  command = plan

  assert {
    condition = (
      length(aws_cloudwatch_log_group.this) == 0 &&
      length(aws_wafv2_web_acl_logging_configuration.this) == 0
    )
    error_message = "logging 을 주지 않았는데 로그 그룹이나 로깅 설정이 생긴다."
  }

  assert {
    condition     = output.logging == null
    error_message = "logging 을 주지 않았는데 출력 logging 이 null 이 아니다."
  }
}

run "logs_blocked_requests_to_cloudwatch_when_enabled" {
  command = apply

  variables {
    logging = {
      log_group_name    = "aws-waf-logs-example"
      retention_in_days = 30
      redacted_headers  = ["authorization", "cookie"]
    }
  }

  assert {
    condition = (
      aws_cloudwatch_log_group.this[0].name == "aws-waf-logs-example" &&
      aws_cloudwatch_log_group.this[0].retention_in_days == 30
    )
    error_message = "로그 그룹 이름 · 보존 일수가 입력 logging 과 다르다."
  }

  assert {
    condition = (
      aws_wafv2_web_acl_logging_configuration.this[0].resource_arn == aws_wafv2_web_acl.this.arn &&
      toset(aws_wafv2_web_acl_logging_configuration.this[0].log_destination_configs) == toset([aws_cloudwatch_log_group.this[0].arn])
    )
    error_message = "로깅 설정이 이 Web ACL 의 로그를 로그 그룹 하나로 보내지 않는다."
  }

  # 차단(BLOCK)된 요청만 남기고 나머지(허용 · 집계 등)는 버린다.
  assert {
    condition = (
      aws_wafv2_web_acl_logging_configuration.this[0].logging_filter[0].default_behavior == "DROP" &&
      length(aws_wafv2_web_acl_logging_configuration.this[0].logging_filter[0].filter) == 1 &&
      alltrue([
        for filter in aws_wafv2_web_acl_logging_configuration.this[0].logging_filter[0].filter :
        filter.behavior == "KEEP" && [
          for condition in filter.condition : condition.action_condition[0].action
        ] == ["BLOCK"]
      ])
    )
    error_message = "로그 필터가 차단(BLOCK)만 남기고 나머지를 버리지 않는다."
  }

  assert {
    condition = toset([
      for field in aws_wafv2_web_acl_logging_configuration.this[0].redacted_fields :
      field.single_header[0].name
    ]) == toset(["authorization", "cookie"])
    error_message = "가린 헤더 집합이 입력 redacted_headers 와 다르다."
  }

  assert {
    condition = (
      output.logging.log_group_name == "aws-waf-logs-example" &&
      output.logging.retention_in_days == 30 &&
      toset(output.logging.redacted_headers) == toset(["authorization", "cookie"])
    )
    error_message = "출력 logging 이 만든 로그 그룹 · 보존 일수 · 가린 헤더와 다르다."
  }
}

# WAF 는 CloudWatch Logs 로그 그룹 이름이 aws-waf-logs- 로 시작해야 받는다.
run "rejects_log_group_without_waf_prefix" {
  command = plan

  variables {
    logging = {
      log_group_name    = "/pokeclip/waf"
      retention_in_days = 30
      redacted_headers  = []
    }
  }

  expect_failures = [var.logging]
}

run "rejects_unsupported_log_retention" {
  command = plan

  variables {
    logging = {
      log_group_name    = "aws-waf-logs-example"
      retention_in_days = 0
      redacted_headers  = []
    }
  }

  expect_failures = [var.logging]
}

# WAF 의 단일 헤더 이름은 소문자로 받는다(provider 문서 single_header).
run "rejects_uppercase_redacted_header" {
  command = plan

  variables {
    logging = {
      log_group_name    = "aws-waf-logs-example"
      retention_in_days = 30
      redacted_headers  = ["Authorization"]
    }
  }

  expect_failures = [var.logging]
}

# --- 웹 ACL 데이터 보호 ---

# protected_headers · protected_query_args 를 주지 않은 호출은 데이터 보호
# 설정이 없다.
run "protects_nothing_by_default" {
  command = plan

  assert {
    condition     = length(aws_wafv2_web_acl.this.data_protection_config) == 0
    error_message = "protected_headers · protected_query_args 를 주지 않았는데 데이터 보호 설정이 생긴다."
  }

  assert {
    condition     = output.protected_headers == [] && output.protected_query_args == []
    error_message = "보호 입력을 주지 않았는데 출력 protected_headers · protected_query_args 가 빈 목록이 아니다."
  }
}

# 웹 ACL 수준 보호는 요청 샘플 · 로그 모두에 걸린다(AWS WAF API
# 「VisibilityConfig」 · 「DataProtectionConfig」).
run "substitutes_protected_headers_in_samples_and_logs" {
  command = plan

  variables {
    protected_headers = ["authorization", "cookie"]
  }

  assert {
    condition = (
      length(aws_wafv2_web_acl.this.data_protection_config) == 1 &&
      length(aws_wafv2_web_acl.this.data_protection_config[0].data_protection) == 1
    )
    error_message = "데이터 보호 설정이 하나(단일 헤더 항목 하나)가 아니다."
  }

  assert {
    condition = alltrue([
      for protection in aws_wafv2_web_acl.this.data_protection_config[0].data_protection :
      (
        protection.action == "SUBSTITUTION" &&
        protection.field[0].field_type == "SINGLE_HEADER" &&
        toset(protection.field[0].field_keys) == toset(["authorization", "cookie"])
      )
    ])
    error_message = "데이터 보호가 authorization · cookie 단일 헤더를 SUBSTITUTION 으로 치환하지 않는다."
  }

  # 규칙 일치 · 속도 규칙 세부(로그의 RuleMatchDetails · rateBasedRuleList)도
  # 가린다 — 예외를 켜면 그 자리에 값이 원문으로 남는다.
  assert {
    condition = alltrue([
      for protection in aws_wafv2_web_acl.this.data_protection_config[0].data_protection :
      protection.exclude_rule_match_details != true && protection.exclude_rate_based_details != true
    ])
    error_message = "데이터 보호가 규칙 일치 · 속도 규칙 세부를 예외로 둔다."
  }

  assert {
    condition     = toset(output.protected_headers) == toset(["authorization", "cookie"])
    error_message = "출력 protected_headers 가 데이터 보호로 가린 헤더와 다르다."
  }
}

# 모듈은 호출자가 준 헤더 이름을 그대로 쓴다 — 이름을 모듈 안에 박아 두면(예:
# authorization · cookie) 다른 이름을 넘긴 이 run 이 깨진다.
run "protects_headers_given_by_caller" {
  command = plan

  variables {
    protected_headers = ["x-example-token"]
  }

  assert {
    condition = [
      for protection in aws_wafv2_web_acl.this.data_protection_config[0].data_protection :
      tolist(protection.field[0].field_keys)
      if protection.field[0].field_type == "SINGLE_HEADER"
    ] == [tolist(["x-example-token"])]
    error_message = "데이터 보호의 단일 헤더 이름이 입력 protected_headers(x-example-token)가 아니다."
  }

  assert {
    condition     = tolist(output.protected_headers) == tolist(["x-example-token"])
    error_message = "출력 protected_headers 가 입력 protected_headers(x-example-token)가 아니다."
  }
}

# 질의 인자는 SINGLE_QUERY_ARGUMENT 로 값만 치환한다 — 로그의 args 에서 그
# 인자 값이 REDACTED 가 된다(AWS WAF 개발자 안내서 「Examples of data
# protection」).
run "substitutes_protected_query_args_in_samples_and_logs" {
  command = plan

  variables {
    protected_query_args = ["example-arg"]
  }

  assert {
    condition = (
      length(aws_wafv2_web_acl.this.data_protection_config) == 1 &&
      length(aws_wafv2_web_acl.this.data_protection_config[0].data_protection) == 1
    )
    error_message = "데이터 보호 설정이 하나(질의 인자 항목 하나)가 아니다."
  }

  assert {
    condition = alltrue([
      for protection in aws_wafv2_web_acl.this.data_protection_config[0].data_protection :
      (
        protection.action == "SUBSTITUTION" &&
        protection.field[0].field_type == "SINGLE_QUERY_ARGUMENT" &&
        tolist(protection.field[0].field_keys) == tolist(["example-arg"]) &&
        protection.exclude_rule_match_details != true &&
        protection.exclude_rate_based_details != true
      )
    ])
    error_message = "데이터 보호가 질의 인자 example-arg 를 예외 없이 SUBSTITUTION 으로 치환하지 않는다."
  }

  assert {
    condition = (
      tolist(output.protected_query_args) == tolist(["example-arg"]) &&
      output.protected_headers == []
    )
    error_message = "출력 protected_query_args · protected_headers 가 데이터 보호로 가린 질의 인자 · 헤더와 다르다."
  }
}

# 둘 다 주면 데이터 보호 설정 하나에 필드 종류마다 항목 하나씩 둔다.
run "protects_headers_and_query_args_together" {
  command = plan

  variables {
    protected_headers    = ["authorization"]
    protected_query_args = ["code", "token"]
  }

  assert {
    condition = (
      length(aws_wafv2_web_acl.this.data_protection_config) == 1 &&
      sort([
        for protection in aws_wafv2_web_acl.this.data_protection_config[0].data_protection :
        protection.field[0].field_type
      ]) == tolist(["SINGLE_HEADER", "SINGLE_QUERY_ARGUMENT"])
    )
    error_message = "데이터 보호 설정 하나에 단일 헤더 · 질의 인자 항목이 하나씩 있지 않다."
  }

  assert {
    condition = (
      tolist(output.protected_headers) == tolist(["authorization"]) &&
      tolist(output.protected_query_args) == tolist(["code", "token"])
    )
    error_message = "출력이 필드 종류별로 가린 이름(헤더 authorization · 질의 인자 code · token)과 다르다."
  }
}

# --- 보호 이름 검증 ---

# FieldToProtect.FieldKeys 는 100개 이하 · 이름마다 1부터 64자다(AWS WAF API).
# 이름은 로그 가림(logging.redacted_headers)처럼 소문자로, 앞뒤 공백 없이
# 받는다.
run "rejects_empty_protected_header" {
  command = plan

  variables {
    protected_headers = [""]
  }

  expect_failures = [var.protected_headers]
}

run "rejects_blank_protected_header" {
  command = plan

  variables {
    protected_headers = [" "]
  }

  expect_failures = [var.protected_headers]
}

run "rejects_protected_header_over_64_chars" {
  command = plan

  variables {
    protected_headers = [format("%065d", 0)]
  }

  expect_failures = [var.protected_headers]
}

run "rejects_uppercase_protected_header" {
  command = plan

  variables {
    protected_headers = ["Authorization"]
  }

  expect_failures = [var.protected_headers]
}

run "rejects_protected_header_with_leading_space" {
  command = plan

  variables {
    protected_headers = [" authorization"]
  }

  expect_failures = [var.protected_headers]
}

run "rejects_protected_header_with_trailing_space" {
  command = plan

  variables {
    protected_headers = ["authorization "]
  }

  expect_failures = [var.protected_headers]
}

run "accepts_100_protected_headers" {
  command = plan

  variables {
    protected_headers = [for index in range(100) : format("x-example-%03d", index)]
  }

  assert {
    condition     = length(output.protected_headers) == 100
    error_message = "헤더 100개(상한)를 다 가리지 않는다."
  }
}

run "rejects_101_protected_headers" {
  command = plan

  variables {
    protected_headers = [for index in range(101) : format("x-example-%03d", index)]
  }

  expect_failures = [var.protected_headers]
}

run "rejects_empty_protected_query_arg" {
  command = plan

  variables {
    protected_query_args = [""]
  }

  expect_failures = [var.protected_query_args]
}

run "rejects_blank_protected_query_arg" {
  command = plan

  variables {
    protected_query_args = [" "]
  }

  expect_failures = [var.protected_query_args]
}

run "rejects_protected_query_arg_over_64_chars" {
  command = plan

  variables {
    protected_query_args = [format("%065d", 0)]
  }

  expect_failures = [var.protected_query_args]
}

run "rejects_uppercase_protected_query_arg" {
  command = plan

  variables {
    protected_query_args = ["Code"]
  }

  expect_failures = [var.protected_query_args]
}

run "rejects_protected_query_arg_with_leading_space" {
  command = plan

  variables {
    protected_query_args = [" code"]
  }

  expect_failures = [var.protected_query_args]
}

run "rejects_protected_query_arg_with_trailing_space" {
  command = plan

  variables {
    protected_query_args = ["code "]
  }

  expect_failures = [var.protected_query_args]
}

run "accepts_100_protected_query_args" {
  command = plan

  variables {
    protected_query_args = [for index in range(100) : format("example-%03d", index)]
  }

  assert {
    condition     = length(output.protected_query_args) == 100
    error_message = "질의 인자 100개(상한)를 다 가리지 않는다."
  }
}

run "rejects_101_protected_query_args" {
  command = plan

  variables {
    protected_query_args = [for index in range(101) : format("example-%03d", index)]
  }

  expect_failures = [var.protected_query_args]
}
