# R16 통과: 확장 대상이 조절 정지 상태를 무시한다(긴급 정지는 운영자 몫).
resource "aws_appautoscaling_target" "worker" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/pokeclip-prod/pokeclip-prod-render"
  min_capacity       = 0
  max_capacity       = 1

  lifecycle {
    ignore_changes = [suspended_state]
  }
}
