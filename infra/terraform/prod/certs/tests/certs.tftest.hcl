# prod/certs 를 mock provider 로 단언한다.
#
# - 공개 존은 이름 조회(pokeclip.com · 공개 존)로 얻고, 검증 레코드는 모두 그
#   존에 둔다.
# - 서울 · us-east-1 인증서 두 벌의 검증 레코드는 정규화한 도메인 이름 키로 한
#   벌만 만든다. 같은 키에 이름이나 값이 다른 원소가 오면 apply 가 멈춘다.
# - provider 별칭마다 다른 인증서 ARN(리전 자리)을 줘, 별칭이 뒤바뀌어
#   연결되면 출력이 바뀌어 실패하게 한다.
#
# 존 id · ARN · 검증 레코드 값은 가짜 값이다.

mock_provider "aws" {
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:ap-northeast-2:ACCOUNT:certificate/example-apne2"
      domain_validation_options = [
        {
          domain_name           = "pokeclip.com"
          resource_record_name  = "_example.pokeclip.com."
          resource_record_type  = "CNAME"
          resource_record_value = "_example.acm-validations.aws."
        },
        {
          domain_name           = "*.pokeclip.com"
          resource_record_name  = "_example.pokeclip.com."
          resource_record_type  = "CNAME"
          resource_record_value = "_example.acm-validations.aws."
        },
      ]
    }
  }
}

mock_provider "aws" {
  alias = "use1"

  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:us-east-1:ACCOUNT:certificate/example-use1"
      domain_validation_options = [
        {
          domain_name           = "pokeclip.com"
          resource_record_name  = "_example.pokeclip.com."
          resource_record_type  = "CNAME"
          resource_record_value = "_example.acm-validations.aws."
        },
        {
          domain_name           = "*.pokeclip.com"
          resource_record_name  = "_example.pokeclip.com."
          resource_record_type  = "CNAME"
          resource_record_value = "_example.acm-validations.aws."
        },
      ]
    }
  }
}

override_data {
  target = data.aws_route53_zone.public
  values = {
    zone_id = "Z0EXAMPLE"
  }
}

run "looks_up_public_zone_by_name" {
  command = plan

  assert {
    condition     = data.aws_route53_zone.public.name == "pokeclip.com" && data.aws_route53_zone.public.private_zone == false
    error_message = "존 조회가 공개 존 pokeclip.com 이 아니다."
  }
}

run "creates_single_validation_record_set_in_public_zone" {
  command = apply

  # 서울 · us-east-1 두 인증서의 원소 넷이 키 하나로 모인다.
  assert {
    condition     = keys(aws_route53_record.validation) == ["pokeclip.com"]
    error_message = "검증 레코드 키 집합이 정규화한 도메인 이름(pokeclip.com 하나)이 아니다."
  }

  assert {
    condition = alltrue([
      for record in aws_route53_record.validation : record.zone_id == "Z0EXAMPLE"
    ])
    error_message = "검증 레코드가 조회한 공개 존에 있지 않다."
  }

  assert {
    condition = (
      aws_route53_record.validation["pokeclip.com"].name == "_example.pokeclip.com." &&
      aws_route53_record.validation["pokeclip.com"].type == "CNAME" &&
      aws_route53_record.validation["pokeclip.com"].records == toset(["_example.acm-validations.aws."])
    )
    error_message = "검증 레코드 이름 · 유형 · 값이 인증서의 검증 옵션과 다르다."
  }
}

run "outputs_match_provider_aliases" {
  command = apply

  assert {
    condition     = output.cert_arn_apne2 == "arn:aws:acm:ap-northeast-2:ACCOUNT:certificate/example-apne2"
    error_message = "출력 cert_arn_apne2 가 서울 provider 의 인증서가 아니다(별칭이 뒤바뀜)."
  }

  assert {
    condition     = output.cert_arn_use1 == "arn:aws:acm:us-east-1:ACCOUNT:certificate/example-use1"
    error_message = "출력 cert_arn_use1 이 us-east-1 별칭의 인증서가 아니다(별칭이 뒤바뀜)."
  }
}

# 두 검증 대기 자원이 같은 레코드 한 벌을 쓴다 — 출력은 검증 대기를 거친다.
run "validation_waits_share_record_set" {
  command = apply

  assert {
    condition = (
      aws_acm_certificate_validation.apne2.validation_record_fqdns == toset([aws_route53_record.validation["pokeclip.com"].fqdn]) &&
      aws_acm_certificate_validation.use1.validation_record_fqdns == toset([aws_route53_record.validation["pokeclip.com"].fqdn])
    )
    error_message = "검증 대기 자원이 공유 검증 레코드의 fqdn 을 쓰지 않는다."
  }

  assert {
    condition = (
      aws_acm_certificate_validation.apne2.certificate_arn == module.cert_apne2.certificate_arn &&
      aws_acm_certificate_validation.use1.certificate_arn == module.cert_use1.certificate_arn
    )
    error_message = "검증 대기 자원이 자기 리전의 인증서를 기다리지 않는다."
  }
}

# us-east-1 인증서의 검증 값이 서울 것과 다르면 레코드 한 벌로 둘을 검증할 수
# 없다 — 레코드를 만들기 전에 멈춘다(값이 알려지는 apply 때 평가).
run "rejects_use1_value_differing_from_apne2" {
  command = apply

  # 앞 run 의 상태가 있으면 인증서가 다시 만들어지지 않아 override 가 쓰이지
  # 않는다 — 빈 상태에서 시작한다.
  state_key = "use1_value_mismatch"

  override_resource {
    target = module.cert_use1.aws_acm_certificate.this
    values = {
      arn = "arn:aws:acm:us-east-1:ACCOUNT:certificate/example-use1"
      domain_validation_options = [
        {
          domain_name           = "pokeclip.com"
          resource_record_name  = "_example.pokeclip.com."
          resource_record_type  = "CNAME"
          resource_record_value = "_other.acm-validations.aws."
        },
        {
          domain_name           = "*.pokeclip.com"
          resource_record_name  = "_example.pokeclip.com."
          resource_record_type  = "CNAME"
          resource_record_value = "_other.acm-validations.aws."
        },
      ]
    }
  }

  expect_failures = [aws_route53_record.validation]
}

run "rejects_use1_name_differing_from_apne2" {
  command = apply

  # 앞 run 의 상태가 있으면 인증서가 다시 만들어지지 않아 override 가 쓰이지
  # 않는다 — 빈 상태에서 시작한다.
  state_key = "use1_name_mismatch"

  override_resource {
    target = module.cert_use1.aws_acm_certificate.this
    values = {
      arn = "arn:aws:acm:us-east-1:ACCOUNT:certificate/example-use1"
      domain_validation_options = [
        {
          domain_name           = "pokeclip.com"
          resource_record_name  = "_other.pokeclip.com."
          resource_record_type  = "CNAME"
          resource_record_value = "_example.acm-validations.aws."
        },
        {
          domain_name           = "*.pokeclip.com"
          resource_record_name  = "_other.pokeclip.com."
          resource_record_type  = "CNAME"
          resource_record_value = "_example.acm-validations.aws."
        },
      ]
    }
  }

  expect_failures = [aws_route53_record.validation]
}
