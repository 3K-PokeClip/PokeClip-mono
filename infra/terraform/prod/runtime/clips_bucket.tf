# clips 버킷(POK-408 · 설계 D3 · K4) — render 일꾼이 완성 클립 · 썸네일을
# 올리고, upload 일꾼과 clip 이 읽는다(웹은 clip 이 준 미리서명 주소로 받음).
# 공개 차단 · ACL 끔 · SSE-S3 · 만료 60일 · 미완료 멀티파트 정리를 둔다
# (rewind 버킷과 같은 꼴 — 만료만 다르다).
#
# 만료 60일(K4): 3번 코드가 「완성 영상 창고 60일 수명 규칙이 치운다」 를
# 전제한다(workers/render S3Store.java:66 · dev 버킷과 같음). 버킷 전체가
# 대상이라 지난 방송 · 카드 사진도 마지막 수정 60일 뒤 사라진다.
#
# 두지 않는 것:
#   - 버전 관리: 결정 50 · 백업 없음(services README).
#   - 버킷 정책: TLS 거부 정책은 런타임 2단 버킷 정책 설계 대상이다(K8).
#   - CORS: 웹은 미리서명 주소를 <video> · <img> 로만 쓴다(D3).

variable "clips_bucket_name" {
  description = "clips 버킷 이름(전역 유일 — 접두 pokeclip-prod-)."
  type        = string
  default     = "pokeclip-prod-clips-apne2"
}

# force_destroy = false — 객체가 남아 있으면 버킷 삭제가 실패한다.
resource "aws_s3_bucket" "clips" {
  bucket        = var.clips_bucket_name
  force_destroy = false
}

resource "aws_s3_bucket_public_access_block" "clips" {
  bucket = aws_s3_bucket.clips.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACL 을 끄고 버킷 소유자가 모든 객체를 소유한다.
resource "aws_s3_bucket_ownership_controls" "clips" {
  bucket = aws_s3_bucket.clips.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "clips" {
  bucket = aws_s3_bucket.clips.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 객체는 마지막 수정 60일 뒤 만료된다(K4). 끝나지 않은 멀티파트 업로드는
# 7일 뒤 중단한다.
resource "aws_s3_bucket_lifecycle_configuration" "clips" {
  bucket = aws_s3_bucket.clips.id

  rule {
    id     = "expire-after-60-days"
    status = "Enabled"

    filter {}

    expiration {
      days = 60
    }
  }

  rule {
    id     = "abort-incomplete-multipart-upload"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
