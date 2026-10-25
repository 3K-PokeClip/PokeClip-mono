# prod/edge 를 mock provider 로 단언한다(계획 M10 c7).
#
# - 출력 여덟은 records · 런타임 2단 · 값 입력 · 랜딩 업로드가 쓰는 계약
#   이름이고, 값은 모듈(apex · media) 출력에서 온다.
# - CLOUDFRONT WAF 는 platform 과 같은 관리 그룹 둘 · 속도 기본 20000 · 차단
#   로그(가림 authorization · cookie) · 같은 두 헤더의 웹 ACL 데이터 보호다.
#   루트 시험은 모듈 안 자원을 주소로 볼 수 없어, 이름 · 범위 · 관리 그룹 ·
#   속도는 모듈 인자의 유일한 출처인 local.cloudfront_waf 로, 로그는 waf_acl
#   출력 logging 으로, 데이터 보호는 출력 protected_headers 로 본다.
# - 표준 로그 로그 그룹은 보존 = 변수이고, enable_standard_logs = false 면
#   media 모듈에 null 을 넘기되 로그 그룹은 남는다.
# - CloudFront Function 로그 그룹 셋은 /aws/cloudfront/function/<함수 이름>
#   · us-east-1 · 보존 = WAF 차단 로그(30일)다.
# - VPC origin 서비스 SG 는 서울 별칭으로 이름 · network vpc_id 로 조회하고,
#   내부 ALB SG 에 그 SG 출처 443 인그레스 하나를 둔다. 조회는 VPC origin
#   하나만 기다려, VPC origin 밖 media 변경 plan 에서도 출처 SG 가 정해져 있다.
# - 앞 루트 출력의 꼴이 어긋나면 terraform_data.remote_contract 가 멈춘다.
#
# 앞 루트 상태는 override_data 로 흉내 낸다. ARN · id · 계정 ID(111122223333)는
# 가짜 값이다. provider 별칭마다 SG 조회 · 규칙 id 의 기본값을 달리 줘, 서울
# 별칭이 아닌 provider 로 연결되면 값이 달라 실패하게 한다. 배포 · 버킷 ·
# KVS 값은 모듈 안 자원을 고정해, 출력이 뒤바뀌어 연결되면 실패하게 한다.

mock_provider "aws" {
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }

  mock_data "aws_region" {
    defaults = {
      region = "us-east-1"
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
    }
  }

  mock_resource "aws_wafv2_web_acl" {
    defaults = {
      arn = "arn:aws:wafv2:us-east-1:111122223333:global/webacl/pokeclip-prod-cloudfront/00000000-0000-0000-0000-0000000000a1"
    }
  }

  # 배포의 function_association 은 provider 가 ARN 꼴을 본다.
  mock_resource "aws_cloudfront_function" {
    defaults = {
      arn = "arn:aws:cloudfront::111122223333:function/example"
    }
  }

  mock_resource "aws_cloudwatch_log_delivery_destination" {
    defaults = {
      arn = "arn:aws:logs:us-east-1:111122223333:delivery-destination:example"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:us-east-1:111122223333:log-group:example-other"
    }
  }

  mock_data "aws_security_group" {
    defaults = {
      id = "sg-0wrongprovider"
    }
  }

  mock_resource "aws_vpc_security_group_ingress_rule" {
    defaults = {
      security_group_rule_id = "sgr-0wrongprovider"
    }
  }
}

mock_provider "aws" {
  alias = "apne2"

  mock_data "aws_security_group" {
    defaults = {
      id = "sg-0vpcorigins"
    }
  }

  mock_resource "aws_vpc_security_group_ingress_rule" {
    defaults = {
      security_group_rule_id = "sgr-0apne2"
    }
  }
}

override_resource {
  target = aws_cloudwatch_log_group.media_access
  values = {
    arn = "arn:aws:logs:us-east-1:111122223333:log-group:pokeclip-prod-media-access"
  }
}

override_resource {
  target = module.apex.aws_s3_bucket.this
  values = {
    bucket                      = "pokeclip-prod-apex-example"
    bucket_regional_domain_name = "pokeclip-prod-apex-example.s3.ap-northeast-2.amazonaws.com"
  }
}

override_resource {
  target = module.apex.aws_cloudfront_distribution.this
  values = {
    id             = "E0APEXEXAMPLE"
    arn            = "arn:aws:cloudfront::111122223333:distribution/E0APEXEXAMPLE"
    domain_name    = "d0apexexample.cloudfront.net"
    hosted_zone_id = "Z0APEXCFEXAMPLE"
  }
}

override_resource {
  target = module.media.aws_cloudfront_distribution.this
  values = {
    id             = "E0MEDIAEXAMPLE"
    arn            = "arn:aws:cloudfront::111122223333:distribution/E0MEDIAEXAMPLE"
    domain_name    = "d0mediaexample.cloudfront.net"
    hosted_zone_id = "Z0MEDIACFEXAMPLE"
  }
}

override_resource {
  target = module.media.aws_cloudfront_key_value_store.this
  values = {
    arn = "arn:aws:cloudfront::111122223333:key-value-store/00000000-0000-0000-0000-0000000000b1"
  }
}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id = "vpc-0example"
    }
  }
}

override_data {
  target = data.terraform_remote_state.certs
  values = {
    outputs = {
      cert_arn_apne2 = "arn:aws:acm:ap-northeast-2:111122223333:certificate/00000000-0000-0000-0000-00000000a002"
      cert_arn_use1  = "arn:aws:acm:us-east-1:111122223333:certificate/00000000-0000-0000-0000-00000000e001"
    }
  }
}

override_data {
  target = data.terraform_remote_state.secrets
  values = {
    outputs = {
      public_key_id = "K0EXAMPLEKEYID"
    }
  }
}

override_data {
  target = data.terraform_remote_state.platform
  values = {
    outputs = {
      internal_alb_arn      = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/pokeclip-prod-internal/0123456789abcdef"
      internal_alb_dns_name = "internal-pokeclip-prod-internal-1234567890.ap-northeast-2.elb.amazonaws.com"
      internal_alb_sg_id    = "sg-0internalalb"
    }
  }
}

override_data {
  target = data.terraform_remote_state.runtime
  values = {
    outputs = {
      rewind_bucket_regional_domain_name = "pokeclip-prod-rewind-example.s3.ap-northeast-2.amazonaws.com"
    }
  }
}

# --- 출력(설계 3-2 · records 계약) ---

run "outputs_carry_module_values" {
  assert {
    condition = (
      output.apex_bucket_name == "pokeclip-prod-apex-example" &&
      output.apex_distribution_id == "E0APEXEXAMPLE" &&
      output.apex_distribution_domain == "d0apexexample.cloudfront.net" &&
      output.apex_distribution_zone_id == "Z0APEXCFEXAMPLE"
    )
    error_message = "apex 출력(apex_bucket_name · apex_distribution_id · _domain · _zone_id)이 apex 모듈의 버킷 · 배포 값이 아니다."
  }

  assert {
    condition = (
      output.media_distribution_arn == "arn:aws:cloudfront::111122223333:distribution/E0MEDIAEXAMPLE" &&
      output.media_distribution_domain == "d0mediaexample.cloudfront.net" &&
      output.media_distribution_zone_id == "Z0MEDIACFEXAMPLE"
    )
    error_message = "media 출력(media_distribution_arn · _domain · _zone_id)이 media 모듈의 배포 값이 아니다."
  }

  assert {
    condition     = output.cdn_kvs_arn == "arn:aws:cloudfront::111122223333:key-value-store/00000000-0000-0000-0000-0000000000b1"
    error_message = "cdn_kvs_arn 이 media 모듈의 KVS ARN 이 아니다."
  }
}

# --- CLOUDFRONT WAF(kty 결정 2026-10-24) ---

run "waf_is_cloudfront_scope_shared_by_both_distributions" {
  command = plan

  assert {
    condition = (
      local.cloudfront_waf.name == "pokeclip-prod-cloudfront" &&
      local.cloudfront_waf.scope == "CLOUDFRONT"
    )
    error_message = "CLOUDFRONT WAF 가 이름 pokeclip-prod-cloudfront · 범위 CLOUDFRONT 가 아니다."
  }

  assert {
    condition = local.cloudfront_waf.managed_rule_groups == [
      "AWSManagedRulesKnownBadInputsRuleSet",
      "AWSManagedRulesAmazonIpReputationList",
    ]
    error_message = "CLOUDFRONT WAF 관리 그룹이 platform 과 같은 둘(KnownBadInputs · IpReputation, 이 순서)이 아니다."
  }

  assert {
    condition     = var.waf_rate_limit == 20000 && local.cloudfront_waf.rate_limit == var.waf_rate_limit
    error_message = "CLOUDFRONT WAF 속도 제한이 변수 waf_rate_limit(기본 20000)이 아니다."
  }
}

run "waf_rate_limit_follows_variable" {
  command = plan

  variables {
    waf_rate_limit = 5000
  }

  assert {
    condition     = local.cloudfront_waf.rate_limit == 5000
    error_message = "CLOUDFRONT WAF 속도 제한이 변수 waf_rate_limit 을 따르지 않는다."
  }
}

run "waf_logs_blocks_with_redacted_credentials" {
  assert {
    condition = (
      module.waf.logging.log_group_name == "aws-waf-logs-pokeclip-prod-cloudfront" &&
      module.waf.logging.retention_in_days == 30
    )
    error_message = "CLOUDFRONT WAF 로그 그룹이 aws-waf-logs-pokeclip-prod-cloudfront · platform 과 같은 보존(30일)이 아니다."
  }

  assert {
    condition     = toset(module.waf.logging.redacted_headers) == toset(["authorization", "cookie"])
    error_message = "CLOUDFRONT WAF 로그가 authorization · cookie 헤더만 가리지 않는다."
  }
}

# 요청 샘플에 media 시청자의 서명 쿠키 · Authorization 이 원문으로 남지 않게
# 웹 ACL 데이터 보호로 두 헤더를 가린다(POK-459).
run "waf_protects_credentials_in_samples" {
  command = plan

  assert {
    condition     = toset(module.waf.protected_headers) == toset(["authorization", "cookie"])
    error_message = "CLOUDFRONT WAF 데이터 보호가 authorization · cookie 헤더만 가리지 않는다."
  }
}

# --- 표준 로그(계획 c8 「표준 로그」) ---

run "standard_logs_go_to_media_access_log_group" {
  assert {
    condition = (
      aws_cloudwatch_log_group.media_access.name == "pokeclip-prod-media-access" &&
      aws_cloudwatch_log_group.media_access.retention_in_days == 7
    )
    error_message = "표준 로그 로그 그룹이 pokeclip-prod-media-access · 보존 7일(log_retention_days 기본값)이 아니다."
  }

  assert {
    condition     = local.media_log_destination == aws_cloudwatch_log_group.media_access.arn
    error_message = "enable_standard_logs 기본값(true)에서 media 모듈 log_destination 이 로그 그룹 ARN 이 아니다."
  }
}

# 로그 그룹을 처음 만드는 plan 에서는 arn 속성이 미정이다(이 파일의
# override_resource 는 apply 때만 값을 준다). 그때도 모듈에 넘기는 ARN 이
# 정해져 있어야 전달 자원 count 가 plan 에서 풀린다.
run "standard_log_destination_is_known_at_first_plan" {
  command = plan

  assert {
    condition     = local.media_log_destination == "arn:aws:logs:us-east-1:111122223333:log-group:pokeclip-prod-media-access"
    error_message = "첫 plan 에서 media 모듈 log_destination 이 us-east-1 로그 그룹 pokeclip-prod-media-access 의 ARN 으로 정해지지 않는다."
  }
}

run "standard_logs_off_passes_null_and_keeps_log_group" {
  variables {
    enable_standard_logs = false
  }

  assert {
    condition     = local.media_log_destination == null
    error_message = "enable_standard_logs = false 인데 media 모듈 log_destination 이 null 이 아니다."
  }

  assert {
    condition     = aws_cloudwatch_log_group.media_access.name == "pokeclip-prod-media-access"
    error_message = "enable_standard_logs = false 에서 표준 로그 로그 그룹이 사라진다(남겨야 한다)."
  }
}

run "standard_log_retention_follows_variable" {
  command = plan

  variables {
    log_retention_days = 14
  }

  assert {
    condition     = aws_cloudwatch_log_group.media_access.retention_in_days == 14
    error_message = "표준 로그 보존이 변수 log_retention_days 를 따르지 않는다."
  }
}

run "standard_log_retention_rejects_non_cloudwatch_value" {
  command = plan

  variables {
    log_retention_days = 8
  }

  expect_failures = [var.log_retention_days]
}

# --- CloudFront Function 로그 그룹(POK-469 · PR 387 보안 L2) ---

# 함수 셋의 console.log 가 가는 us-east-1 로그 그룹을 이 루트가 미리 만들어
# 보존을 건다. 이름은 /aws/cloudfront/function/<함수 이름>, 보존은 WAF 차단
# 로그와 같다. 리전은 자원의 region 값으로 본다 — mock 은 provider 리전을
# 모르므로, 자원이 region 을 적지 않으면 mock 이 지은 값이 나와 실패하고 다른
# 리전을 적어도 실패한다. ARN 앞머리로는 기본 provider 인지 본다 — 서울 별칭
# mock 에는 로그 그룹 기본값이 없어 다른 값이 나온다.
run "cloudfront_function_log_groups_keep_waf_retention" {
  assert {
    condition = {
      for key, group in aws_cloudwatch_log_group.cloudfront_function :
      key => group.name
      } == {
      live_auth    = "/aws/cloudfront/function/pokeclip-prod-live-auth"
      deny_default = "/aws/cloudfront/function/pokeclip-prod-deny-default"
      apex_request = "/aws/cloudfront/function/pokeclip-prod-apex-request"
    }
    error_message = "함수 로그 그룹이 /aws/cloudfront/function/ + 함수 셋(live-auth · deny-default · apex-request)의 이름이 아니다."
  }

  assert {
    condition = alltrue([
      for group in aws_cloudwatch_log_group.cloudfront_function :
      group.retention_in_days == 30 &&
      group.retention_in_days == local.waf_log_retention_days
    ])
    error_message = "함수 로그 그룹 보존이 WAF 차단 로그와 같은 30일(local.waf_log_retention_days)이 아니다."
  }

  assert {
    condition = alltrue([
      for group in aws_cloudwatch_log_group.cloudfront_function :
      startswith(group.arn, "arn:aws:logs:us-east-1:")
    ])
    error_message = "함수 로그 그룹을 기본(us-east-1) provider 가 아닌 provider 로 만든다."
  }

  assert {
    condition = alltrue([
      for group in aws_cloudwatch_log_group.cloudfront_function :
      group.region == "us-east-1"
    ])
    error_message = "함수 로그 그룹의 region 이 us-east-1 이 아니다(자원에 적지 않았거나 다른 리전)."
  }
}

# --- VPC origin 서비스 SG → 내부 ALB 인그레스(계획 c8) ---

run "looks_up_vpc_origin_service_sg_in_prod_vpc" {
  assert {
    condition = (
      data.aws_security_group.vpc_origin_service.name == "CloudFront-VPCOrigins-Service-SG" &&
      data.aws_security_group.vpc_origin_service.vpc_id == "vpc-0example"
    )
    error_message = "VPC origin 서비스 SG 조회가 이름 CloudFront-VPCOrigins-Service-SG · network 의 vpc_id 가 아니다."
  }

  assert {
    condition     = data.aws_security_group.vpc_origin_service.id == "sg-0vpcorigins"
    error_message = "VPC origin 서비스 SG 를 서울 별칭(aws.apne2)이 아닌 provider 로 조회한다."
  }
}

run "internal_alb_accepts_https_from_vpc_origins" {
  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.internal_alb_from_vpc_origins.security_group_id == "sg-0internalalb" &&
      aws_vpc_security_group_ingress_rule.internal_alb_from_vpc_origins.referenced_security_group_id == "sg-0vpcorigins"
    )
    error_message = "인그레스 규칙이 platform 내부 ALB SG ← VPC origin 서비스 SG 가 아니다."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.internal_alb_from_vpc_origins.ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.internal_alb_from_vpc_origins.from_port == 443 &&
      aws_vpc_security_group_ingress_rule.internal_alb_from_vpc_origins.to_port == 443
    )
    error_message = "인그레스 규칙이 TCP 443 하나가 아니다."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.internal_alb_from_vpc_origins.security_group_rule_id == "sgr-0apne2"
    error_message = "인그레스 규칙을 서울 별칭(aws.apne2)이 아닌 provider 로 만든다."
  }
}

# 앞 run 들이 apply 로 상태를 남긴 뒤, media 모듈의 VPC origin 밖 변경(여기서는
# 표준 로그 끄기 — 전달 자원 삭제)만 있는 plan 이다. SG 조회가 media 모듈
# 전체를 기다리면 조회가 apply 로 밀려 규칙의 출처 SG 가 미정이 되고, plan 에
# 규칙 변경 예고가 끼어 런북 「전달 자원 삭제만 · 그 밖 0」 판정이 깨진다.
run "standard_logs_off_plan_keeps_ingress_source_sg_known" {
  command = plan

  variables {
    enable_standard_logs = false
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.internal_alb_from_vpc_origins.referenced_security_group_id == "sg-0vpcorigins"
    error_message = "표준 로그 끄기 plan 에서 인그레스 규칙의 출처 SG 가 VPC origin 서비스 SG 로 정해져 있지 않다(SG 조회가 VPC origin 밖 media 변경에 끌려 apply 로 밀린다)."
  }

  # 고리의 input 이 VPC origin id 인지만 본다. data 의 depends_on 줄 자체는
  # mock 으로 순서를 볼 수 없어 이 시험이 잡지 못한다(guard R15 가 지킨다).
  assert {
    condition     = terraform_data.vpc_origin_ready.input == module.media.vpc_origin_id
    error_message = "SG 조회가 기다리는 terraform_data.vpc_origin_ready 가 media 모듈의 VPC origin id 에 걸려 있지 않다."
  }
}

# --- 앞 루트 출력의 꼴(terraform_data.remote_contract) ---

run "rejects_network_vpc_id_not_vpc" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id = "subnet-0example"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "rejects_secrets_public_key_id_not_cloudfront_key" {
  command = plan

  override_data {
    target = data.terraform_remote_state.secrets
    values = {
      outputs = {
        public_key_id = "arn:aws:cloudfront::111122223333:public-key/K0EXAMPLEKEYID"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "rejects_platform_public_alb_dns_as_internal" {
  command = plan

  override_data {
    target = data.terraform_remote_state.platform
    values = {
      outputs = {
        internal_alb_arn      = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/pokeclip-prod-internal/0123456789abcdef"
        internal_alb_dns_name = "pokeclip-prod-public-1234567890.ap-northeast-2.elb.amazonaws.com"
        internal_alb_sg_id    = "sg-0internalalb"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "rejects_platform_internal_alb_sg_id_not_sg" {
  command = plan

  override_data {
    target = data.terraform_remote_state.platform
    values = {
      outputs = {
        internal_alb_arn      = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/pokeclip-prod-internal/0123456789abcdef"
        internal_alb_dns_name = "internal-pokeclip-prod-internal-1234567890.ap-northeast-2.elb.amazonaws.com"
        internal_alb_sg_id    = "pokeclip-prod-internal"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}
