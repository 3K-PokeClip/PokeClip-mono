# R16: 무시 목록이 식(all)이다 — desired_count 리터럴만 인정한다.
resource "aws_ecs_service" "this" {
  name          = local.full_name
  desired_count = 1

  lifecycle {
    ignore_changes = all
  }
}
