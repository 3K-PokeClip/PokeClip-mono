# 자격 증명은 실행자의 AWS 프로필(AWS_PROFILE) — 지금은 kty IAM 사용자
# (결정 44 ②, IdC 전환 보류). 코드에 두지 않는다.
provider "aws" {
  region = var.region
}
