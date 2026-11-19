# R16: 다른 속성만 무시한다 — desired_count 가 없다.
resource "aws_ecs_service" "this" {
  name          = local.full_name
  desired_count = 1

  lifecycle {
    ignore_changes = [task_definition]
  }
}
