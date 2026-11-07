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

output "deployment_minimum_healthy_percent" {
  description = "서비스 배포 최소 비율(%). 넘기지 않았으면 null(ECS 기본)."
  value       = aws_ecs_service.this.deployment_minimum_healthy_percent
}

output "deployment_maximum_percent" {
  description = "서비스 배포 최대 비율(%). 넘기지 않았으면 null(ECS 기본)."
  value       = aws_ecs_service.this.deployment_maximum_percent
}

# 아래 셋은 만든 자원의 값 그대로다 — 호출 루트 시험이 모듈 블록 배선(다른
# 서비스의 값을 받지 않았는지)을 본다. 비밀은 ARN 만 들고 값은 없다.
output "task_definition" {
  description = "태스크 정의의 family · cpu · 메모리(문자열 — ECS 표기) · 임시 디스크 GiB(없으면 null) · 컨테이너 정의 JSON."
  value = {
    family                = aws_ecs_task_definition.this.family
    cpu                   = aws_ecs_task_definition.this.cpu
    memory                = aws_ecs_task_definition.this.memory
    ephemeral_storage_gib = one(aws_ecs_task_definition.this.ephemeral_storage[*].size_in_gib)
    container_definitions = aws_ecs_task_definition.this.container_definitions
  }
}

output "task_role_policy" {
  description = "작업 역할 정책 JSON. 문장이 없으면 null."
  value       = one(aws_iam_role_policy.task[*].policy)
}

output "egress_rules" {
  description = "서비스 SG 이그레스 규칙(키 = egress_targets 의 키) — 포트 · 상대(cidr_ipv4 · prefix_list_id · security_group_id 중 하나) · 설명."
  value = {
    for key, rule in aws_vpc_security_group_egress_rule.this : key => {
      port              = rule.from_port
      cidr_ipv4         = rule.cidr_ipv4
      prefix_list_id    = rule.prefix_list_id
      security_group_id = rule.referenced_security_group_id
      description       = rule.description
    }
  }
}
