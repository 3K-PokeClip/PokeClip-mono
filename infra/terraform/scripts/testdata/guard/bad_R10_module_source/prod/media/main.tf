# R10: media_host 밖의 모듈을 부른다 — 그 모듈의 자원은 이 검사 밖에서 역할에
# 정책을 붙일 수 있다(module 은 ../../modules/media_host 하나만).
module "extra" {
  source = "../../modules/ecs_service"
}
