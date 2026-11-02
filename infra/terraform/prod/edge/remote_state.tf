# 앞 루트 출력(설계 3-2 · 3-6). 같은 환경 안의 값이라 terraform_remote_state
# 로 읽는다. 상태 버킷은 서울이다 — 기본 provider(us-east-1)와 상관없이
# var.region 으로 읽는다.

data "terraform_remote_state" "network" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.network
    region = var.region
  }
}

data "terraform_remote_state" "certs" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.certs
    region = var.region
  }
}

data "terraform_remote_state" "secrets" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.secrets
    region = var.region
  }
}

data "terraform_remote_state" "platform" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.platform
    region = var.region
  }
}

data "terraform_remote_state" "runtime" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.runtime
    region = var.region
  }
}

locals {
  network  = data.terraform_remote_state.network.outputs
  certs    = data.terraform_remote_state.certs.outputs
  secrets  = data.terraform_remote_state.secrets.outputs
  platform = data.terraform_remote_state.platform.outputs
  runtime  = data.terraform_remote_state.runtime.outputs
}

# 앞 루트 출력을 모듈 · SG 조회 · 규칙에 쓰기 전에 꼴을 본다. 출력 이름이
# 어긋나 빠진 값도 여기서 멈춘다(can 이 false). 모듈 변수 검증이 보지 않는
# 꼴(vpc · SG id · 공개키 id · 내부 ALB DNS 의 internal- 접두)도 여기서 본다 —
# 공개 ALB 를 media 원본으로 잘못 넘기면 VPC origin 이 겨눌 수 없다.
resource "terraform_data" "remote_contract" {
  lifecycle {
    precondition {
      condition     = can(regex("^vpc-[A-Za-z0-9]+$", local.network.vpc_id))
      error_message = "network 출력 vpc_id 가 없거나 VPC id(vpc-…)가 아니다."
    }

    precondition {
      condition     = can(regex("^arn:aws:acm:us-east-1:[0-9]{12}:certificate/[A-Za-z0-9-]+$", local.certs.cert_arn_use1))
      error_message = "certs 출력 cert_arn_use1 이 없거나 us-east-1 ACM 인증서 ARN 이 아니다."
    }

    # CloudFront 공개키 id 는 K 로 시작하는 대문자 · 숫자다(ARN 이 아니다).
    precondition {
      condition     = can(regex("^K[A-Z0-9]+$", local.secrets.public_key_id))
      error_message = "secrets 출력 public_key_id 가 없거나 CloudFront 공개키 id(K…)가 아니다."
    }

    precondition {
      condition = (
        can(regex("^arn:aws:elasticloadbalancing:[a-z0-9-]+:[0-9]{12}:loadbalancer/app/[A-Za-z0-9-]+/[0-9a-f]+$", local.platform.internal_alb_arn)) &&
        can(regex("^internal-[A-Za-z0-9-]+\\.[a-z0-9-]+\\.elb\\.amazonaws\\.com$", local.platform.internal_alb_dns_name)) &&
        can(regex("^sg-[A-Za-z0-9]+$", local.platform.internal_alb_sg_id))
      )
      error_message = "platform 출력 internal_alb_arn · internal_alb_dns_name · internal_alb_sg_id 가 없거나 ALB ARN · 내부 ALB DNS 이름(internal-<이름>.<리전>.elb.amazonaws.com) · SG id(sg-…) 꼴이 아니다."
    }

    precondition {
      condition     = can(regex("^[a-z0-9][a-z0-9.-]*\\.s3\\.[a-z0-9-]+\\.amazonaws\\.com$", local.runtime.rewind_bucket_regional_domain_name))
      error_message = "runtime 출력 rewind_bucket_regional_domain_name 이 없거나 <버킷>.s3.<리전>.amazonaws.com 꼴이 아니다."
    }
  }
}
