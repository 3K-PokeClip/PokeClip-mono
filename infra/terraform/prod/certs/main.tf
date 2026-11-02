# prod 인증서 두 벌(설계 3-1 의 ⑥) — 서울(ALB) · us-east-1(CloudFront). 둘 다
# pokeclip.com + *.pokeclip.com 이다.
#
# 공개 존은 nonprod/dns-legacy 가 소유한다. 이 루트는 존을 이름으로 조회하고
# (같은 이름의 공개 존이 둘이면 조회가 실패한다 — fail-closed), 그 존 안에
# 검증 레코드를 별도 자원으로 둔다. dns-legacy 의 레코드는 건드리지 않는다.
#
# 같은 계정 · 같은 도메인의 인증서는 리전이 달라도 같은 DNS 검증 레코드를
# 쓴다(AWS ACM 「DNS validation」). 그래서 두 인증서의 검증 옵션을 정규화한
# 도메인 이름 키로 합쳐 레코드를 한 벌만 만들고(모듈의 레코드 생성은 끔), 두
# 검증 대기 자원이 그 레코드를 쓴다. 인스턴스마다 레코드를 두면 둘째가 「이미
# 있음」 으로 실패한다.

locals {
  domain = "pokeclip.com"
  sans   = ["*.pokeclip.com"]

  # 검증 레코드 키 = 정규화한 도메인 이름(`*.` 를 뗌). plan 때 알려지는 값에서
  # 만든다 — 검증 레코드 이름은 apply 전 미지값이다.
  validation_keys = toset([for name in concat([local.domain], local.sans) : trimprefix(name, "*.")])

  # 키 → 두 인증서에서 그 키로 모인 검증 옵션 목록(묶음 모드). 서울 것을 앞에
  # 둬 첫 원소가 서울 인증서의 것이 된다.
  validation_options = {
    for option in concat(
      tolist(module.cert_apne2.domain_validation_options),
      tolist(module.cert_use1.domain_validation_options),
    ) :
    trimprefix(option.domain_name, "*.") => option...
  }
}

data "aws_route53_zone" "public" {
  name         = local.domain
  private_zone = false
}

module "cert_apne2" {
  source = "../../modules/acm_certificate"

  domain                    = local.domain
  sans                      = local.sans
  zone_id                   = data.aws_route53_zone.public.zone_id
  create_validation_records = false
}

module "cert_use1" {
  source = "../../modules/acm_certificate"

  providers = {
    aws = aws.use1
  }

  domain                    = local.domain
  sans                      = local.sans
  zone_id                   = data.aws_route53_zone.public.zone_id
  create_validation_records = false
}

# allow_overwrite 는 쓰지 않는다 — 공유 존의 기존 레코드를 덮는 길이 되기
# 때문이다. 같은 이름이 있으면 apply 가 「이미 있음」 으로 멈춘다(O7b 고유
# 확인).
resource "aws_route53_record" "validation" {
  for_each = local.validation_keys

  zone_id = data.aws_route53_zone.public.zone_id
  name    = local.validation_options[each.key][0].resource_record_name
  type    = local.validation_options[each.key][0].resource_record_type
  records = [local.validation_options[each.key][0].resource_record_value]
  ttl     = 60

  lifecycle {
    # 두 인증서에서 같은 키로 모인 원소(서울 · us-east-1 의 apex · 와일드카드)
    # 가 모두 같은 레코드여야 한 벌로 둘을 검증한다. 다르면 레코드를 만들기
    # 전에 멈춘다. 값이 알려질 때(첫 apply 는 두 인증서 생성 뒤) 평가된다 —
    # check 블록은 경고만 내므로 쓰지 않는다.
    precondition {
      condition = alltrue([
        for option in local.validation_options[each.key] :
        option.resource_record_name == local.validation_options[each.key][0].resource_record_name &&
        option.resource_record_type == local.validation_options[each.key][0].resource_record_type &&
        option.resource_record_value == local.validation_options[each.key][0].resource_record_value
      ])
      error_message = "서울 · us-east-1 인증서의 ACM 검증 레코드 이름 · 유형 · 값이 같은 도메인 키 안에서 다르다 — 레코드 한 벌로 검증할 수 없다."
    }
  }
}

resource "aws_acm_certificate_validation" "apne2" {
  certificate_arn         = module.cert_apne2.certificate_arn
  validation_record_fqdns = [for record in aws_route53_record.validation : record.fqdn]
}

resource "aws_acm_certificate_validation" "use1" {
  provider = aws.use1

  certificate_arn         = module.cert_use1.certificate_arn
  validation_record_fqdns = [for record in aws_route53_record.validation : record.fqdn]
}
