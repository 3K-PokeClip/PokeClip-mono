# R16 통과: 모듈 서비스가 원하는 수를 무시한다(1 은 생성 초기값).
resource "aws_ecs_service" "this" {
  name          = local.full_name
  desired_count = 1

  lifecycle {
    ignore_changes = [desired_count]
  }
}
