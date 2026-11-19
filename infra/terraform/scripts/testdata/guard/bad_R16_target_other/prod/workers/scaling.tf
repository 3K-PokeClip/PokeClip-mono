# R16: 다른 속성만 무시한다 — suspended_state 가 없다.
resource "aws_appautoscaling_target" "worker" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/pokeclip-prod/pokeclip-prod-render"
  min_capacity       = 0
  max_capacity       = 1

  lifecycle {
    ignore_changes = [tags]
  }
}
