output "name_servers" {
  description = "공개 존 네임서버 — 리스 만료 대비(설계 9-2)의 NS 보관."
  value       = aws_route53_zone.legacy.name_servers
}

output "records" {
  description = "공개 존의 나머지 레코드 전 목록 — 리스 만료 대비(설계 9-2)의 레코드 보관."
  value = {
    for key, record in aws_route53_record.legacy : key => {
      name    = record.name
      type    = record.type
      ttl     = record.ttl
      records = record.records
    }
  }
}
