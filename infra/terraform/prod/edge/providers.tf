# 자격 증명은 실행자의 AWS 프로필(AWS_PROFILE) — 지금은 kty IAM 사용자
# (결정 44 ②, IdC 전환 보류). 코드에 두지 않는다.
#
# CloudFront 배포 · 함수 · KVS · CLOUDFRONT WAF · 배포의 표준 로그는
# us-east-1 에서 만든다 — 그래서 기본 provider 가 us-east-1 이다(설계 3-1 의
# ⑩). 서울 자원(VPC origin 서비스 SG 조회 · 내부 ALB SG 규칙)은 별칭
# aws.apne2 로 만든다. 상태 버킷 · 앞 루트 상태는 서울이다(var.region).
provider "aws" {
  region = "us-east-1"

  # prod 자원 표지 — 같은 리스 계정의 nonprod 자원과 가른다. nonprod import
  # 루트에는 넣지 않는다(넣으면 import 무변경이 깨진다).
  default_tags {
    tags = {
      Project       = "pokeclip"
      Environment   = "prod"
      ManagedBy     = "terraform"
      TerraformRoot = "prod/edge"
    }
  }
}

provider "aws" {
  alias  = "apne2"
  region = var.region

  default_tags {
    tags = {
      Project       = "pokeclip"
      Environment   = "prod"
      ManagedBy     = "terraform"
      TerraformRoot = "prod/edge"
    }
  }
}
