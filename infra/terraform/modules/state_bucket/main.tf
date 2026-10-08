# Terraform 상태 버킷: 버전 관리 · 기본 암호화 · 퍼블릭 차단 · 삭제 방지.
# 잠금은 S3 잠금 파일(use_lockfile)이라 DynamoDB 테이블은 두지 않는다.

resource "aws_s3_bucket" "this" {
  bucket = var.name

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
