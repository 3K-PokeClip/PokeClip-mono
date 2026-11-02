# prod 엣지 비밀 그릇 세 개(값 없음)와 CloudFront 서명 공개키.
# 비밀 값은 운영 단계에서 Terraform 밖으로 넣는다 — 상태에 남기지 않는다
# (runbooks/edge-secrets.md).

locals {
  # 용도 키 → 비밀 이름. 이름 규약은 pokeclip/prod/<용도>(계획 3절 「한 계정
  # 규약」). scripts/put-secret-from-file.sh 의 종류와 같은 키다.
  secret_names = {
    for purpose in ["signing-key", "cdn-secret", "test-stream-key"] :
    purpose => "pokeclip/prod/${purpose}"
  }
}

module "secret_shells" {
  source = "../../modules/secret_shells"

  names = values(local.secret_names)
}

module "cloudfront_public_key" {
  source = "../../modules/cloudfront_public_key"

  name           = "pokeclip-prod-signing-key"
  public_key_pem = var.public_key_pem
}
