output "instance_ids" {
  description = "상자 키 → 인스턴스 id."
  value       = { for key, instance in aws_instance.this : key => instance.id }
}

output "media_sg_id" {
  description = "Media SG id — 런타임 2단이 Media → auth 이그레스 · auth 인그레스 ← Media 에 쓴다."
  value       = aws_security_group.media.id
}

output "instance_role_arn" {
  description = "Media 인스턴스 역할 ARN."
  value       = aws_iam_role.media.arn
}

output "rendered_user_data" {
  description = "시험용 — 상자 키 → 압축 전 user_data(ARN · 다이제스트 · 판뿐, 비밀 없음). 루트는 다시 내보내지 않는다."
  value       = local.rendered_user_data
}

output "template_vars" {
  description = "시험용 — compose · nginx 템플릿에 넘긴 변수(비밀 없음). 루트는 다시 내보내지 않는다."
  value       = local.template_vars
}
