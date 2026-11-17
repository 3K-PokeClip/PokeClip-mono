# prod ECR 저장소(설계 3-1 의 ⑦). 이미지 푸시는 GitHub Actions(prod CI
# B12)와 kty 로컬 대행 경로(runbooks/prod-artifacts.md)가 한다.
#
# CI 역할 둘(ECR 푸시 · apex 업로드)과 GitHub OIDC 공급자 참조(data
# source — 만들지 않음)는 ci_roles.tf 에 있다. 수명주기 정책은 두지 않는다
# — tfvars 에 박은 다이제스트의 이미지가 정책으로 지워지지 않게 한다.
# 암호화는 기본값(AES256)이다.

resource "aws_ecr_repository" "this" {
  for_each = var.repository_keys

  name                 = "pokeclip-prod/${each.key}"
  image_tag_mutability = var.image_tag_immutable ? "IMMUTABLE" : "MUTABLE"

  image_scanning_configuration {
    scan_on_push = var.scan_on_push
  }
}
