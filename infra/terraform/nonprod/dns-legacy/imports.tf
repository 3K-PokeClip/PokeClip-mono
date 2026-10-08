# import 블록은 상태에만 기록한다(운영 O1 — kty 승인 apply).
# 레코드 import id 꼴은 <존 id>_<이름>_<유형> 이다.

import {
  to = aws_route53_zone.legacy
  id = var.zone_id
}

import {
  to = aws_route53_record.ns
  id = "${var.zone_id}_${var.zone_name}_NS"
}

import {
  for_each = var.records

  to = aws_route53_record.legacy[each.key]
  id = "${var.zone_id}_${each.value.name}_${each.value.type}"
}
