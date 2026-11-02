# R12 ②: 존 주소를 legacy 에서 public 으로 바꿨다(moved 가 있어도 위반).
# 운영 표지에서만 드러나므로 guard_test.sh 가 저장소 밖 사본으로 돌린다.
moved {
  from = aws_route53_zone.legacy
  to   = aws_route53_zone.public
}

resource "aws_route53_zone" "public" {
  name = "example.com"

  lifecycle {
    prevent_destroy = true
  }
}
