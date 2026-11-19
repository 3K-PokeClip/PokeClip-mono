# R16: 모듈 서비스가 원하는 수를 무시하지 않는다(lifecycle 없음) — 0대마다
# plan 에 0 → 1 이 보이고 apply 가 일꾼을 되살린다.
resource "aws_ecs_service" "this" {
  name          = local.full_name
  desired_count = 1
}
