# 자격 증명은 실행자의 AWS 프로필(AWS_PROFILE) — 지금은 kty IAM 사용자
# (결정 44 ②, IdC 전환 보류). 코드에 두지 않는다.
#
# prod 자원 표지 — 같은 리스 계정의 nonprod 자원과 가른다. 버킷 정책은 태그를
# 받지 않지만, 이 루트에 자원을 더할 때 표지가 빠지지 않게 둔다.
provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project       = "pokeclip"
      Environment   = "prod"
      ManagedBy     = "terraform"
      TerraformRoot = "prod/runtime-2"
    }
  }
}
