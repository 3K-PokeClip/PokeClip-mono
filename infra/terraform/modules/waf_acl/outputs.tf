output "web_acl_arn" {
  description = "Web ACL ARN(ALB 연결 · CloudFront web_acl_id 에 넘긴다)."
  value       = aws_wafv2_web_acl.this.arn
}

output "protected_headers" {
  description = "웹 ACL 데이터 보호로 값을 가리는 헤더 이름(요청 샘플 · 로그 모두). 끄면 빈 목록."
  value = flatten([
    for config in aws_wafv2_web_acl.this.data_protection_config : [
      for protection in config.data_protection : protection.field[0].field_keys
    ]
  ])
}

output "logging" {
  description = "만든 트래픽 로그 설정(로그 그룹 이름 · 보존 일수 · 가린 헤더). 로깅을 끄면 null."
  value = var.logging == null ? null : {
    log_group_name    = aws_cloudwatch_log_group.this[0].name
    retention_in_days = aws_cloudwatch_log_group.this[0].retention_in_days
    redacted_headers = [
      for field in aws_wafv2_web_acl_logging_configuration.this[0].redacted_fields :
      field.single_header[0].name
    ]
  }
}
