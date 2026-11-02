output "certificate_arn" {
  description = "인증서 ARN. 검증 완료를 기다리지 않는다 — 소비자에게는 호출 루트의 검증 대기 자원을 거친 값을 넘긴다."
  value       = aws_acm_certificate.this.arn
}

output "domain_validation_options" {
  description = "ACM 검증 옵션 — 레코드 생성을 끈 호출 루트가 검증 레코드를 만들 때 쓴다."
  value       = aws_acm_certificate.this.domain_validation_options
}
