output "alb_arn" {
  description = "ALB ARN."
  value       = aws_lb.this.arn
}

output "dns_name" {
  description = "ALB DNS 이름."
  value       = aws_lb.this.dns_name
}

output "zone_id" {
  description = "ALB 의 Route 53 호스팅 존 id(별칭 레코드용)."
  value       = aws_lb.this.zone_id
}

output "sg_id" {
  description = "ALB SG id. 규칙은 호출하는 루트가 붙인다."
  value       = aws_security_group.this.id
}

output "target_group_arns" {
  description = "target_groups 키 → 대상 그룹 ARN."
  value = {
    for key, group in aws_lb_target_group.this : key => group.arn
  }
}
