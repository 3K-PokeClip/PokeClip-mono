output "service_name" {
  description = "ECS 서비스 이름."
  value       = aws_ecs_service.this.name
}

output "task_role_arn" {
  description = "작업 역할 ARN."
  value       = aws_iam_role.task.arn
}

output "execution_role_arn" {
  description = "실행 역할 ARN."
  value       = aws_iam_role.execution.arn
}

output "sg_id" {
  description = "서비스 SG id — 호출자가 인그레스 규칙 · 상대 이그레스에 쓴다."
  value       = aws_security_group.this.id
}

output "discovery_name" {
  description = "Cloud Map 서비스 이름(<discovery_name>.<네임스페이스>)."
  value       = aws_service_discovery_service.this.name
}
