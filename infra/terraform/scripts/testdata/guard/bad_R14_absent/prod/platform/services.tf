# R14: 다리 이름이 바뀌어 고정 주소의 다리가 없다.
resource "terraform_data" "routed_target_groups" {
  input = module.public_alb.target_group_arns

  depends_on = [module.public_alb]
}
