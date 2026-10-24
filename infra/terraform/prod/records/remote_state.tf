# 앞 루트 출력(설계 3-2). 같은 환경 안의 값이라 terraform_remote_state 로
# 읽는다 — platform 은 공개 ALB · NLB, edge 는 apex · media 배포.

data "terraform_remote_state" "platform" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.platform
    region = var.region
  }
}

data "terraform_remote_state" "edge" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.edge
    region = var.region
  }
}

locals {
  platform = data.terraform_remote_state.platform.outputs
  edge     = data.terraform_remote_state.edge.outputs
}

# 앞 루트 출력을 별칭 대상에 쓰기 전에 꼴을 본다. 출력 이름이 어긋나 빠진
# 값(갈래 간 계약 이름)도 여기서 멈춘다. 레코드가 0 인 기본값에서도 본다 —
# 이름을 열기 전에 계약 어긋남을 드러내려고. 존 id 는 Route 53 호스팅 존
# id(Z…), DNS 이름은 자원 종류마다 AWS 가 주는 꼴이다 — ALB
# <이름>.<리전>.elb.amazonaws.com · NLB <이름>.elb.<리전>.amazonaws.com ·
# CloudFront d<영숫자>.cloudfront.net.
resource "terraform_data" "remote_contract" {
  lifecycle {
    precondition {
      condition = (
        can(regex("^[A-Za-z0-9-]+\\.[a-z0-9-]+\\.elb\\.amazonaws\\.com$", local.platform.public_alb_dns_name)) &&
        can(regex("^Z[A-Z0-9]+$", local.platform.public_alb_zone_id))
      )
      error_message = "platform 출력 public_alb_dns_name · public_alb_zone_id 가 없거나 ALB DNS 이름(<이름>.<리전>.elb.amazonaws.com) · 존 id(Z…) 꼴이 아니다."
    }

    precondition {
      condition = (
        can(regex("^[A-Za-z0-9-]+\\.elb\\.[a-z0-9-]+\\.amazonaws\\.com$", local.platform.nlb_dns_name)) &&
        can(regex("^Z[A-Z0-9]+$", local.platform.nlb_zone_id))
      )
      error_message = "platform 출력 nlb_dns_name · nlb_zone_id 가 없거나 NLB DNS 이름(<이름>.elb.<리전>.amazonaws.com) · 존 id(Z…) 꼴이 아니다."
    }

    precondition {
      condition = (
        can(regex("^d[a-z0-9]+\\.cloudfront\\.net$", local.edge.apex_distribution_domain)) &&
        can(regex("^Z[A-Z0-9]+$", local.edge.apex_distribution_zone_id))
      )
      error_message = "edge 출력 apex_distribution_domain · apex_distribution_zone_id 가 없거나 CloudFront 도메인(d….cloudfront.net) · 존 id(Z…) 꼴이 아니다."
    }

    precondition {
      condition = (
        can(regex("^d[a-z0-9]+\\.cloudfront\\.net$", local.edge.media_distribution_domain)) &&
        can(regex("^Z[A-Z0-9]+$", local.edge.media_distribution_zone_id))
      )
      error_message = "edge 출력 media_distribution_domain · media_distribution_zone_id 가 없거나 CloudFront 도메인(d….cloudfront.net) · 존 id(Z…) 꼴이 아니다."
    }
  }
}
