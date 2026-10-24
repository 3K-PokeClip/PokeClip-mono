# rewind 버킷(POK-308 · 설계 D10) — 사이드카가 되감기 세그먼트를 올리고 edge 의
# rewind-s3 오리진이 읽는다(CONTRACT-runtime.md 「런타임 1단 → media · edge」).
# 공개 차단 · ACL 끔 · SSE-S3 · 미완료 멀티파트 정리만 둔다.
#
# 두지 않는 것:
#   - 버전 관리: 결정 50. 목록을 다시 PUT 하고 CAS 조건부 쓰기로 갱신하므로 이전
#     버전이 쓸모없이 쌓인다.
#   - 버킷 정책: OAC 허용문은 edge 배포 ARN 이 있어야 해서 런타임 2단이 만든다.
#   - 만료: 되감기 세그먼트는 수명주기로 지우지 않는다.
#   - CORS: 어느 루트가 가질지 미확인이다(edge 에 묻는다 — PR 한계).

variable "rewind_bucket_name" {
  description = "rewind 버킷 이름(전역 유일 — 접두 pokeclip-prod-)."
  type        = string
  default     = "pokeclip-prod-rewind-apne2"
}

# force_destroy = false — 객체가 남아 있으면 버킷 삭제가 실패한다.
resource "aws_s3_bucket" "rewind" {
  bucket        = var.rewind_bucket_name
  force_destroy = false
}

resource "aws_s3_bucket_public_access_block" "rewind" {
  bucket = aws_s3_bucket.rewind.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACL 을 끄고 버킷 소유자가 모든 객체를 소유한다.
resource "aws_s3_bucket_ownership_controls" "rewind" {
  bucket = aws_s3_bucket.rewind.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "rewind" {
  bucket = aws_s3_bucket.rewind.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 끝나지 않은 멀티파트 업로드는 7일 뒤 중단한다. 만료 규칙은 두지 않는다.
resource "aws_s3_bucket_lifecycle_configuration" "rewind" {
  bucket = aws_s3_bucket.rewind.id

  rule {
    id     = "abort-incomplete-multipart-upload"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

output "rewind_bucket_name" {
  description = "rewind 버킷 이름(edge 오리진)."
  value       = aws_s3_bucket.rewind.bucket
}

output "rewind_bucket_arn" {
  description = "rewind 버킷 ARN(media 업로드 정책 · 런타임 2단 버킷 정책)."
  value       = aws_s3_bucket.rewind.arn
}

output "rewind_bucket_regional_domain_name" {
  description = "rewind 버킷 지역 도메인 이름(edge rewind-s3 오리진)."
  value       = aws_s3_bucket.rewind.bucket_regional_domain_name
}
