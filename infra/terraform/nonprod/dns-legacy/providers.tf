# 자격 증명은 실행자의 IdC 프로필(AWS_PROFILE)에서 온다. 코드에 두지 않는다.
provider "aws" {
  region = var.region
}
