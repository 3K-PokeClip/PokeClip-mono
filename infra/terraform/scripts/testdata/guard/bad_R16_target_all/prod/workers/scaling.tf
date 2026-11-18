# R16: 무시 목록이 식(all)이다 — suspended_state 리터럴만 인정한다.
resource "aws_appautoscaling_target" "worker" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/pokeclip-prod/pokeclip-prod-render"
  min_capacity       = 0
  max_capacity       = 1

  lifecycle {
    ignore_changes = all
  }
}
