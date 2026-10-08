# 모든 규칙을 통과하는 형태: 비밀 그릇만(값 없음) · SG 규칙은 별도 자원.
resource "aws_secretsmanager_secret" "signing_key" {
  name = "example"
}

resource "aws_security_group" "separate" {
  name        = "example"
  description = "example"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.separate.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.separate.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# 주석 · 블록 주석 · heredoc 속 금지 선언은 실제 선언이 아니므로 통과한다.
# resource "aws_secretsmanager_secret_version" "commented" {}
// resource "aws_cloudfrontkeyvaluestore_key" "commented" {}
/*
resource "aws_secretsmanager_secret_version" "block_commented" {
  secret_id = "example"
}
*/

resource "aws_ssm_parameter" "doc" {
  name  = "example"
  type  = "String"
  value = <<-EOT
    resource "aws_secretsmanager_secret_version" "in_heredoc" {}
  EOT
}

# SG 안의 주석 · 문자열 속 중괄호와 "ingress {" 글자는 블록으로 세지 않는다.
resource "aws_security_group" "braces_in_text" {
  name        = "example-text"
  description = "not a block: ingress { }"
  # ingress {
  tags = {
    Note = "}"
  }
}

# 하이픈이 든 heredoc 종료자도 heredoc 으로 지운다. 본문 속 /* 는 주석이 아니다.
resource "aws_ssm_parameter" "doc_hyphen" {
  name  = "example-hyphen"
  type  = "String"
  value = <<-DOC-TEXT
    /* resource "aws_secretsmanager_secret_version" "in_heredoc" {}
  DOC-TEXT
}

# 보간 속 따옴표 · 중괄호 · # · << 는 문자열이라 주석 · heredoc · 블록으로 세지 않는다.
resource "aws_security_group" "interp" {
  name        = "example-interp"
  description = "x ${format("}{ # <<EOT %s", "y")} z"
  tags = {
    Note = "$${not_interp} ${lower("A")}"
  }
}

# 이름이 비슷한 다른 유형은 대상이 아니다.
resource "aws_security_group_rule" "legacy_name_only" {
  type              = "ingress"
  security_group_id = aws_security_group.interp.id
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
}

# 속성 이름이 resource 로 시작해도 선언이 아니다.
locals {
  resourceaws_secretsmanager_secret_version = "example"
}
