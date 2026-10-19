# DNS 검증 ACM 인증서. 리전은 호출 루트가 넘긴 provider 로 정한다(서울 ·
# us-east-1 별칭). 검증 완료 대기는 두지 않는다 — 검증 레코드를 누가 만드는지
# (이 모듈 · 호출 루트)와 상관없이 호출 루트가 둔다.
#
# 검증 레코드는 공유 존 안의 별도 레코드다. allow_overwrite 는 쓰지 않는다 —
# 공유 존의 기존 레코드를 덮는 길이 되기 때문이다(같은 이름이 있으면 apply 가
# 「이미 있음」 으로 멈춘다).

resource "aws_acm_certificate" "this" {
  domain_name               = var.domain
  subject_alternative_names = var.sans
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

locals {
  # 검증 레코드 키 = 정규화한 도메인 이름(`*.` 를 뗌). apex 와 와일드카드는
  # 같은 검증 레코드를 쓰므로 키 하나로 모인다. 키는 plan 때 알려지는 입력
  # 에서만 만든다 — 검증 레코드 이름은 apply 전 미지값이다.
  validation_keys = toset([for name in concat([var.domain], var.sans) : trimprefix(name, "*.")])

  # 키 → 그 키로 모인 검증 옵션 목록(묶음 모드).
  validation_options = {
    for option in aws_acm_certificate.this.domain_validation_options :
    trimprefix(option.domain_name, "*.") => option...
  }
}

resource "aws_route53_record" "validation" {
  for_each = var.create_validation_records ? local.validation_keys : toset([])

  zone_id = var.zone_id
  name    = local.validation_options[each.key][0].resource_record_name
  type    = local.validation_options[each.key][0].resource_record_type
  records = [local.validation_options[each.key][0].resource_record_value]
  ttl     = 60

  lifecycle {
    # 같은 키로 모인 원소가 모두 같은 레코드여야 레코드 하나로 모두를
    # 검증한다. 값이 알려질 때(첫 apply 는 인증서 생성 뒤) 평가된다.
    precondition {
      condition = alltrue([
        for option in local.validation_options[each.key] :
        option.resource_record_name == local.validation_options[each.key][0].resource_record_name &&
        option.resource_record_type == local.validation_options[each.key][0].resource_record_type &&
        option.resource_record_value == local.validation_options[each.key][0].resource_record_value
      ])
      error_message = "같은 도메인 키로 모인 ACM 검증 레코드의 이름 · 유형 · 값이 서로 다르다 — 레코드 하나로 검증할 수 없다."
    }
  }
}
