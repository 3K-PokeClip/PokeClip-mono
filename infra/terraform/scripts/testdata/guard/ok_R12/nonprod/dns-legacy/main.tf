# R12 를 지키는 공개 존 루트: 주소 aws_route53_zone.legacy · 리터럴 prevent_destroy.
resource "aws_route53_zone" "legacy" {
  name = "example.com"

  lifecycle {
    prevent_destroy = true
  }
}
