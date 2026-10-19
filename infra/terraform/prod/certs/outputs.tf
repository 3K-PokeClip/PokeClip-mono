# 소비 루트와의 계약(설계 3-2) — 이름을 바꾸지 않는다. 검증 대기 자원을 거친
# 값이라, 소비 루트는 발급이 끝난 인증서만 받는다.

output "cert_arn_apne2" {
  description = "서울 인증서 ARN(pokeclip.com · *.pokeclip.com) — platform 의 공개 ALB · 내부 ALB."
  value       = aws_acm_certificate_validation.apne2.certificate_arn
}

output "cert_arn_use1" {
  description = "us-east-1 인증서 ARN(pokeclip.com · *.pokeclip.com) — edge 의 CloudFront 배포."
  value       = aws_acm_certificate_validation.use1.certificate_arn
}
