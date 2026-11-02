# rewind 버킷(POK-308 · 설계 D10) — 사이드카가 되감기 세그먼트를 올리고 edge 의
# rewind-s3 오리진이 읽는다(CONTRACT-runtime.md 「런타임 1단 → media · edge」).
# 공개 차단 · ACL 끔 · SSE-S3 · 미완료 멀티파트 정리 · CORS 를 둔다. segment
# 창고를 겸하므로(1단-다 D4) 편집기의 트랙별 미리 듣기가 clip 이 서명한 조각
# 주소를 브라우저 fetch 로 받는다(POK-389) — CORS 는 그 앱 출처의 GET · HEAD 만
# 허락한다(CONTRACT-runtime.md 「작업 역할 대상」).
#
# 두지 않는 것:
#   - 버전 관리: 결정 50. 목록을 다시 PUT 하고 CAS 조건부 쓰기로 갱신하므로 이전
#     버전이 쓸모없이 쌓인다.
#   - 버킷 정책: OAC 허용문은 edge 배포 ARN 이 있어야 해서 런타임 2단이 만든다.
#   - 만료: 되감기 세그먼트는 수명주기로 지우지 않는다.

variable "rewind_bucket_name" {
  description = "rewind 버킷 이름(전역 유일 — 접두 pokeclip-prod-)."
  type        = string
  default     = "pokeclip-prod-rewind-apne2"
}

# 앱 호스트(K7)의 출처다. 같은 호스트가 profile_photo_base_url(auth_settings.tf)
# · platform 의 app_host · cors_allowed_origins 에도 있다 — 바꾸면 함께 본다.
# 출처는 scheme://호스트[:포트] 이고 와일드카드 · 경로가 없다.
variable "app_origin" {
  description = "편집기 웹 앱의 출처(https://호스트[:포트]) — rewind 버킷 CORS 가 허락하는 유일한 출처."
  type        = string
  default     = "https://app.pokeclip.com"

  validation {
    condition     = can(regex("^https://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]+)?$", var.app_origin))
    error_message = "app_origin 이 https://호스트[:포트] 꼴이 아니다(소문자 호스트 · 와일드카드 · 경로 · 끝 / 없음)."
  }
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

# 미리 듣기 fetch 는 헤더 · 자격 증명을 싣지 않는 단순 요청이라 사전 요청이
# 없다 — 허용 헤더 · 노출 헤더 · 캐시 시간을 두지 않는다.
resource "aws_s3_bucket_cors_configuration" "rewind" {
  bucket = aws_s3_bucket.rewind.id

  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = [var.app_origin]
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
