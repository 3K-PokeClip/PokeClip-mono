# SRT 수신용 인터넷 대면 NLB(설계 3-1 의 ⑧ · 3-3 media_ingest · 5-5). Media
# AZ 의 퍼블릭 서브넷 하나에만 둔다 — Media 상자가 그 AZ 에 하나뿐이라서다.
# 대상 등록 · NLB → Media 이그레스는 media 루트가 만든다(5-5 — 여기서 만들면
# 뒤 루트의 media_sg_id 가 필요해 순환한다).

resource "aws_security_group" "nlb" {
  name        = "${var.name}-nlb"
  description = "SRT ingest NLB"
  vpc_id      = var.vpc_id
}

# 출처마다 규칙 하나 — 키 = CIDR 이라 출처 집합을 단언할 수 있다.
resource "aws_vpc_security_group_ingress_rule" "srt" {
  for_each = toset(var.ingest_allowed_cidrs)

  security_group_id = aws_security_group.nlb.id
  description       = "SRT ingest"
  ip_protocol       = "udp"
  from_port         = var.srt_port
  to_port           = var.srt_port
  cidr_ipv4         = each.key
}

resource "aws_lb" "this" {
  name               = "${var.name}-nlb"
  load_balancer_type = "network"
  internal           = false
  subnets            = [var.subnet_id]
  security_groups    = [aws_security_group.nlb.id]
}

resource "aws_lb_target_group" "srt" {
  name                 = "${var.name}-ingest"
  vpc_id               = var.vpc_id
  protocol             = "UDP"
  port                 = var.srt_port
  target_type          = "instance"
  deregistration_delay = var.deregistration_delay

  # 등록 해제 때 남은 UDP 흐름을 끊는다 — 교체(설계 5-3) 뒤 송출이 지연 시간
  # 동안 옛 상자에 붙어 있지 않게.
  connection_termination = true

  # UDP 로는 헬스를 볼 수 없어 Media 헬스 포트를 TCP 로 본다.
  health_check {
    protocol = "TCP"
    port     = tostring(var.health_port)
  }
}

resource "aws_lb_listener" "srt" {
  load_balancer_arn = aws_lb.this.arn
  protocol          = "UDP"
  port              = var.srt_port

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.srt.arn
  }
}
