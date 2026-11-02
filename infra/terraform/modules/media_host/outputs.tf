output "instance_ids" {
  description = "상자 키 → 인스턴스 id."
  value       = { for key, instance in aws_instance.this : key => instance.id }
}

output "media_sg_id" {
  description = "Media SG id — 다른 루트가 Media 를 상대로 규칙을 걸 때 쓴다. Media ↔ auth · RDS 짝은 이 모듈이 만든다(kty 결정 2026-10-25 (나))."
  value       = aws_security_group.media.id
}

output "instance_role_arn" {
  description = "Media 인스턴스 역할 ARN."
  value       = aws_iam_role.media.arn
}

output "auth_resolve_rules" {
  description = "시험용 — Media → auth 이그레스 · auth 인그레스 ← Media 의 auth SG id · 포트. 루트는 다시 내보내지 않는다."
  value = {
    egress = {
      auth_sg_id = aws_vpc_security_group_egress_rule.media["auth_resolve"].referenced_security_group_id
      port       = aws_vpc_security_group_egress_rule.media["auth_resolve"].from_port
    }
    ingress = {
      auth_sg_id = aws_vpc_security_group_ingress_rule.auth_from_media.security_group_id
      port       = aws_vpc_security_group_ingress_rule.auth_from_media.from_port
    }
  }
}

output "rendered_user_data" {
  description = "시험용 — 상자 키 → 압축 전 user_data(ARN · 다이제스트 · 판뿐, 비밀 없음). 루트는 다시 내보내지 않는다."
  value       = local.rendered_user_data
}

output "template_vars" {
  description = "시험용 — compose · nginx 템플릿에 넘긴 변수(비밀 없음). 루트는 다시 내보내지 않는다."
  value       = local.template_vars
}
