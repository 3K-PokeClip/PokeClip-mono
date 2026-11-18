# R16: 확장 대상이 조절 정지 상태를 무시하지 않는다(lifecycle 없음) — 다음
# apply 가 긴급 정지를 푼다.
resource "aws_appautoscaling_target" "worker" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/pokeclip-prod/pokeclip-prod-render"
  min_capacity       = 0
  max_capacity       = 1
}
