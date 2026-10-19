# ACM 인증서 모듈(설계 3-3)을 mock provider 로 단언한다. apex 와 와일드카드는
# 같은 DNS 검증 레코드를 쓰므로, 검증 레코드는 정규화한 도메인 이름(`*.` 를
# 뗀 이름)마다 하나다. 키는 plan 때 알려지는 입력(domain · sans)에서만 나온다.
# 검증 레코드 이름 · 값 · 존 id 는 가짜 값이다.

mock_provider "aws" {}

# domain_validation_options 는 계산 속성이다. ACM 이 apex · 와일드카드에 주는
# 꼴(같은 검증 레코드 두 원소)로 plan 때 고정한다.
override_resource {
  target          = aws_acm_certificate.this
  override_during = plan
  values = {
    arn = "arn:aws:acm:ap-northeast-2:ACCOUNT:certificate/example"
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

variables {
  domain  = "pokeclip.com"
  sans    = ["*.pokeclip.com"]
  zone_id = "Z0EXAMPLE"
}

run "requests_dns_validated_certificate_with_wildcard_san" {
  command = plan

  assert {
    condition     = aws_acm_certificate.this.domain_name == "pokeclip.com" && aws_acm_certificate.this.validation_method == "DNS"
    error_message = "인증서가 domain 의 DNS 검증 인증서가 아니다."
  }

  assert {
    condition     = aws_acm_certificate.this.subject_alternative_names == toset(["*.pokeclip.com"])
    error_message = "인증서 SAN 이 *.pokeclip.com 이 아니다."
  }
}

run "keys_validation_records_by_normalized_domain" {
  command = plan

  # 두 원소(apex · 와일드카드)가 키 하나로 모인다.
  assert {
    condition     = keys(aws_route53_record.validation) == ["pokeclip.com"]
    error_message = "검증 레코드 키 집합이 정규화한 도메인 이름(pokeclip.com 하나)이 아니다."
  }

  assert {
    condition = alltrue([
      for record in aws_route53_record.validation : record.zone_id == "Z0EXAMPLE"
    ])
    error_message = "검증 레코드가 입력 zone_id 의 존에 있지 않다."
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

run "skips_validation_records_when_disabled" {
  command = plan

  variables {
    create_validation_records = false
  }

  assert {
    condition     = length(aws_route53_record.validation) == 0
    error_message = "create_validation_records = false 인데 검증 레코드가 있다."
  }
}

# 같은 키로 묶인 원소의 값이 다르면 레코드 하나로 둘을 검증할 수 없다 —
# 아무 원소나 골라 만들지 않고 멈춘다.
run "rejects_different_values_under_same_key" {
  command = plan

  override_resource {
    target          = aws_acm_certificate.this
    override_during = plan
    values = {
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
          resource_record_value = "_other.acm-validations.aws."
        },
      ]
    }
  }

  expect_failures = [aws_route53_record.validation]
}

run "rejects_different_names_under_same_key" {
  command = plan

  override_resource {
    target          = aws_acm_certificate.this
    override_during = plan
    values = {
      domain_validation_options = [
        {
          domain_name           = "pokeclip.com"
          resource_record_name  = "_example.pokeclip.com."
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

run "outputs_certificate_arn_and_validation_options" {
  command = plan

  assert {
    condition     = output.certificate_arn == "arn:aws:acm:ap-northeast-2:ACCOUNT:certificate/example"
    error_message = "출력 certificate_arn 이 인증서 ARN 이 아니다."
  }

  assert {
    condition     = output.domain_validation_options == aws_acm_certificate.this.domain_validation_options
    error_message = "출력 domain_validation_options 가 인증서의 검증 옵션이 아니다."
  }
}
