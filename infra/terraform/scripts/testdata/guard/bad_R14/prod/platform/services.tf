# R14: 다리가 공개 ALB 를 기다리지 않는다(depends_on 없음).
resource "terraform_data" "public_routed_target_groups" {
  input = module.public_alb.target_group_arns
}
