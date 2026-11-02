# prod 운영 이름 별칭 레코드(설계 3-1 의 ⑫ · 계획 M11). 공개 존
# pokeclip.com 은 nonprod/dns-legacy 가 소유한다. 이 루트는 존을 이름으로
# 조회하고(같은 이름의 공개 존이 둘이면 조회가 실패한다 — fail-closed), 그 존
# 안에 레코드를 별도 자원으로 둔다. dns-legacy 의 dev · media-dev 레코드는
# 건드리지 않는다.
#
# 이름은 enabled_records 에 적은 것만 연다(O8 — 이름마다 kty 승인 · apply).

locals {
  domain = "pokeclip.com"

  # 별칭 대상 — 앞 루트 출력. 빠진 출력은 null 로 두고
  # terraform_data.remote_contract 가 계약 이름과 함께 멈춘다(레코드는 그
  # 뒤에 평가된다).
  alias_targets = {
    apex_distribution = {
      dns_name = try(local.edge.apex_distribution_domain, null)
      zone_id  = try(local.edge.apex_distribution_zone_id, null)
    }
    media_distribution = {
      dns_name = try(local.edge.media_distribution_domain, null)
      zone_id  = try(local.edge.media_distribution_zone_id, null)
    }
    public_alb = {
      dns_name = try(local.platform.public_alb_dns_name, null)
      zone_id  = try(local.platform.public_alb_zone_id, null)
    }
    nlb = {
      dns_name = try(local.platform.nlb_dns_name, null)
      zone_id  = try(local.platform.nlb_zone_id, null)
    }
  }

  # 이름 → 레코드 이름 · 별칭 대상 · 유형. apex 배포는 IPv6 를 켜 두므로
  # apex · www 는 A + AAAA 다. www → apex 301 은 apex 배포의 함수가 한다.
  record_specs = {
    apex   = { name = local.domain, target = "apex_distribution", types = ["A", "AAAA"] }
    www    = { name = "www.${local.domain}", target = "apex_distribution", types = ["A", "AAAA"] }
    app    = { name = "app.${local.domain}", target = "public_alb", types = ["A"] }
    api    = { name = "api.${local.domain}", target = "public_alb", types = ["A"] }
    media  = { name = "media.${local.domain}", target = "media_distribution", types = ["A"] }
    ingest = { name = "ingest.${local.domain}", target = "nlb", types = ["A"] }
  }

  # for_each 키 = <이름>-<유형>(예: apex-AAAA).
  records = merge([
    for record in var.enabled_records : {
      for type in local.record_specs[record].types :
      "${record}-${type}" => {
        name   = local.record_specs[record].name
        type   = type
        target = local.record_specs[record].target
      }
    }
  ]...)
}

data "aws_route53_zone" "public" {
  name         = local.domain
  private_zone = false
}

# allow_overwrite 는 쓰지 않는다 — 공유 존의 기존 레코드를 덮는 길이 되기
# 때문이다. 같은 이름 · 유형이 있으면 apply 가 「이미 있음」 으로 멈춘다(O8
# 고유 확인).
resource "aws_route53_record" "alias" {
  for_each = local.records

  zone_id = data.aws_route53_zone.public.zone_id
  name    = each.value.name
  type    = each.value.type

  alias {
    name                   = local.alias_targets[each.value.target].dns_name
    zone_id                = local.alias_targets[each.value.target].zone_id
    evaluate_target_health = false
  }

  depends_on = [terraform_data.remote_contract]
}
