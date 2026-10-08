output "name_servers" {
  description = "옛 존 네임서버 — 존 이전 해석 비교(9-2)의 대상."
  value       = aws_route53_zone.legacy.name_servers
}

output "records" {
  description = "옛 존의 나머지 레코드 전 목록 — 새 존 · dns-dev 복사 근거."
  value = {
    for key, record in aws_route53_record.legacy : key => {
      name    = record.name
      type    = record.type
      ttl     = record.ttl
      records = record.records
    }
  }
}
