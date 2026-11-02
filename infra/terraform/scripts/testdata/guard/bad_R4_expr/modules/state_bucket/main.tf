# R4: 값이 false 인 식은 삭제 방지가 아니다(리터럴 true 만 인정).
resource "aws_s3_bucket" "this" {
  bucket = "example"

  lifecycle {
    prevent_destroy = true != true
  }
}
