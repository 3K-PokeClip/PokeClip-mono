# 프로필 사진 버킷(POK-427 · 설계 D5) — auth 가 회원 사진을 올리고 읽고 지운다
# (키 profile-photos/<id>/<자리> — PhotoStorage.java:72). 브라우저는 창고를 직접
# 읽지 않는다 — auth 가 표 서명을 확인한 뒤 바이트를 내보낸다
# (ProfilePhotoController.java). 공개 차단 · ACL 끔 · SSE-S3 · 미완료 멀티파트
# 정리만 둔다(rewind 버킷과 같은 꼴).
#
# 두지 않는 것:
#   - 버전 관리: 결정 50. 자리 둘을 번갈아 덮어써 이전 버전이 쓸모없이 쌓인다.
#   - 버킷 정책: TLS 거부 정책은 런타임 2단 버킷 정책 설계 대상이다(K8).
#   - 만료: 사진은 수명주기로 지우지 않는다 — 탈퇴 때 auth 가 지운다.
#   - CORS: 올리기는 auth 서버가 한다.
#   - CloudFront: auth 가 직접 내보낸다.

variable "profile_photo_bucket_name" {
  description = "프로필 사진 버킷 이름(전역 유일 — 접두 pokeclip-prod-)."
  type        = string
  default     = "pokeclip-prod-profile-photos-apne2"
}

# force_destroy = false — 객체가 남아 있으면 버킷 삭제가 실패한다.
resource "aws_s3_bucket" "profile_photo" {
  bucket        = var.profile_photo_bucket_name
  force_destroy = false
}

resource "aws_s3_bucket_public_access_block" "profile_photo" {
  bucket = aws_s3_bucket.profile_photo.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACL 을 끄고 버킷 소유자가 모든 객체를 소유한다.
resource "aws_s3_bucket_ownership_controls" "profile_photo" {
  bucket = aws_s3_bucket.profile_photo.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "profile_photo" {
  bucket = aws_s3_bucket.profile_photo.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 끝나지 않은 멀티파트 업로드는 7일 뒤 중단한다. 만료 규칙은 두지 않는다.
resource "aws_s3_bucket_lifecycle_configuration" "profile_photo" {
  bucket = aws_s3_bucket.profile_photo.id

  rule {
    id     = "abort-incomplete-multipart-upload"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
