# R12 ③: 같은 루트의 두 번째 존에 삭제 방지가 없다(legacy 에는 있다).
resource "aws_route53_zone" "legacy" {
  name = "example.com"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_route53_zone" "extra" {
  name = "sub.example.com"
}
