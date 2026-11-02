# import 블록은 상태에만 기록한다(운영 O1 — kty 승인 apply).
# 규칙마다 블록을 따로 둔다: M12 가 8082 블록 · 변수만 지우게(guard R9).

import {
  to = aws_instance.dev
  id = var.instance_id
}

import {
  to = aws_eip.dev
  id = var.eip_allocation_id
}

import {
  to = aws_security_group.dev
  id = var.security_group_id
}

import {
  to = aws_vpc_security_group_ingress_rule.dev["80"]
  id = var.ingress_rule_id_80
}

import {
  to = aws_vpc_security_group_ingress_rule.dev["443"]
  id = var.ingress_rule_id_443
}

import {
  to = aws_vpc_security_group_ingress_rule.dev["8082"]
  id = var.ingress_rule_id_8082
}

import {
  to = aws_vpc_security_group_egress_rule.dev
  id = var.egress_rule_id
}
