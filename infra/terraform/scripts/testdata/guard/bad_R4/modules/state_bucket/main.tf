# R4: 상태 버킷에 prevent_destroy = true 가 없다.
resource "aws_s3_bucket" "this" {
  bucket = var.name
}
