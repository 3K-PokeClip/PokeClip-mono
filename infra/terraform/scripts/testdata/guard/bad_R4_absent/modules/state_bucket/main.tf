# R4: 상태 버킷 모듈에 aws_s3_bucket 이 없다(다른 자원의 삭제 방지로는 채워지지 않음).
resource "aws_s3_bucket_versioning" "this" {
  bucket = "example"

  versioning_configuration {
    status = "Enabled"
  }

  lifecycle {
    prevent_destroy = true
  }
}
