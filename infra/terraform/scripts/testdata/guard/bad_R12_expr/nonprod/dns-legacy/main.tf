# R12 ③: 식 · 문자열 · lifecycle 없음은 삭제 방지가 아니다(리터럴 true 만 인정).
variable "keep" {
  type    = bool
  default = true
}

resource "aws_route53_zone" "legacy" {
  name = "example.com"

  lifecycle {
    prevent_destroy = var.keep
  }
}

resource "aws_route53_zone" "string_true" {
  name = "a.example.com"

  lifecycle {
    prevent_destroy = "true"
  }
}

resource "aws_route53_zone" "expression" {
  name = "b.example.com"

  lifecycle {
    prevent_destroy = true != true
  }
}

resource "aws_route53_zone" "no_lifecycle" {
  name = "c.example.com"
}
