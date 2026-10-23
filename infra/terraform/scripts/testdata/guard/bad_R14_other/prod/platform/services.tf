# R14: 다리가 공개 ALB 가 아닌 것을 기다린다.
resource "terraform_data" "public_routed_target_groups" {
  input = module.public_alb.target_group_arns

  depends_on = [aws_ecs_cluster.this]
}
