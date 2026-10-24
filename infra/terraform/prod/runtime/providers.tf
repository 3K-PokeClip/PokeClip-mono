# prod 자원 표지 — 같은 리스 계정의 nonprod 자원과 가른다. nonprod import
# 루트에는 넣지 않는다(넣으면 import 무변경이 깨진다). mock 시험이 tags_all 을
# 보지 못해 지도를 local 로 두고 시험이 직접 본다.
locals {
  default_tags = {
    Project       = "pokeclip"
    Environment   = "prod"
    ManagedBy     = "terraform"
    TerraformRoot = "prod/runtime"
  }
}

# 자격 증명은 실행자의 AWS 프로필(AWS_PROFILE) — 지금은 kty IAM 사용자
# (결정 44 ②, IdC 전환 보류). 코드에 두지 않는다.
provider "aws" {
  region = var.region

  default_tags {
    tags = local.default_tags
  }
}
