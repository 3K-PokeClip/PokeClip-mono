# Terraform 상태 버킷: 버전 관리 · 기본 암호화 · 퍼블릭 차단 · ACL 끔 ·
# TLS 강제 정책 · 이전 버전 정리 · 삭제 방지.
# 잠금은 S3 잠금 파일(use_lockfile)이라 DynamoDB 테이블은 두지 않는다.
# 상태에는 자원 속성이 평문으로 남으므로, 상태를 읽을 수 있는 권한은 그
# 속성(비밀 포함)을 읽을 수 있는 권한이다(README 「상태 읽기 권한」).

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

# ACL 을 끄고 버킷 소유자가 모든 객체를 소유한다.
resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# 평문 HTTP 와 TLS 1.2 미만 요청을 누구에게나 거부한다. 허용 문장은 두지
# 않는다(접근은 IAM 이 정한다).
resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.this.arn, "${aws_s3_bucket.this.arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      },
      {
        Sid       = "DenyOutdatedTls"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.this.arn, "${aws_s3_bucket.this.arn}/*"]
        Condition = { NumericLessThan = { "s3:TlsVersion" = 1.2 } }
      },
    ]
  })

  # 퍼블릭 차단을 먼저 걸어 두고 정책을 붙인다.
  depends_on = [aws_s3_bucket_public_access_block.this]
}

# 이전 상태 버전(지운 값이 남아 있을 수 있음)은 90일 뒤 지우고, 끝나지 않은
# 멀티파트 업로드는 7일 뒤 중단한다.
resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    id     = "expire-noncurrent-state"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # 버전 관리가 켜진 뒤에 이전 버전 규칙을 건다.
  depends_on = [aws_s3_bucket_versioning.this]
}
