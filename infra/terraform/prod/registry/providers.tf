# 자격 증명은 실행자의 AWS 프로필(AWS_PROFILE) — 지금은 kty IAM 사용자
# (결정 44 ②, IdC 전환 보류). 코드에 두지 않는다.
provider "aws" {
  region = var.region

  # prod 자원 표지 — 같은 리스 계정의 nonprod 자원과 가른다. nonprod import
  # 루트에는 넣지 않는다(넣으면 import 무변경이 깨진다).
  default_tags {
    tags = {
      Project       = "pokeclip"
      Environment   = "prod"
      ManagedBy     = "terraform"
      TerraformRoot = "prod/registry"
    }
  }
}
