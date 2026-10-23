output "nlb_dns_name" {
  description = "NLB DNS 이름."
  value       = aws_lb.this.dns_name
}

output "nlb_zone_id" {
  description = "NLB 호스팅 존 id(별칭 레코드용)."
  value       = aws_lb.this.zone_id
}

output "nlb_sg_id" {
  description = "NLB SG id — media 가 NLB → Media 이그레스 · Media 인그레스 ← NLB 규칙에 쓴다."
  value       = aws_security_group.nlb.id
}

output "target_group_arn" {
  description = "SRT(UDP) 대상 그룹 ARN — media 가 상자를 등록한다."
  value       = aws_lb_target_group.srt.arn
}
