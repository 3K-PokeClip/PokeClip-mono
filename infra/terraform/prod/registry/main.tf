# prod ECR 저장소(설계 3-1 의 ⑦ — ECR 만). 이미지 푸시는 당분간 kty 로컬이다
# (runbooks/prod-artifacts.md · 결정 18 · 38차).
#
# CI 푸시 역할 · apex 업로드 역할 · GitHub OIDC 공급자(자원 · data source
# 모두)는 이 루트에 두지 않는다 — CI 자동 푸시를 켜는 후속 승인 지점(설계
# 8절 · 40차)의 PR 이 함께 더한다. 수명주기 정책은 두지 않는다 — tfvars 에
# 박은 다이제스트의 이미지가 정책으로 지워지지 않게 한다. 암호화는 기본값
# (AES256)이다.

resource "aws_ecr_repository" "this" {
  for_each = var.repository_keys

  name                 = "pokeclip-prod/${each.key}"
  image_tag_mutability = var.image_tag_immutable ? "IMMUTABLE" : "MUTABLE"

  image_scanning_configuration {
    scan_on_push = var.scan_on_push
  }
}
