# 옛 공개 존(pokeclip.com)과 레코드를 무변경으로 import 하기 위한 선언.
# 존 이전 동안 이 루트는 NS TTL 만 바꾸고, 이전이 끝나면 destroy 한다.
# 운영 O1 의 첫 plan 이 「import N · 생성 0 · 수정 0 · 삭제 0」이 아니면
# apply 하지 않고 이 선언 · tfvars 를 실물에 맞춘다.

resource "aws_route53_zone" "legacy" {
  name    = var.zone_name
  comment = var.zone_comment
  tags    = var.zone_tags
}

resource "aws_route53_record" "ns" {
  zone_id = aws_route53_zone.legacy.zone_id
  name    = var.zone_name
  type    = "NS"
  ttl     = var.ns_ttl
  records = aws_route53_zone.legacy.name_servers
}

resource "aws_route53_record" "legacy" {
  for_each = var.records

  zone_id = aws_route53_zone.legacy.zone_id
  name    = each.value.name
  type    = each.value.type
  ttl     = each.value.ttl
  records = each.value.records
}
