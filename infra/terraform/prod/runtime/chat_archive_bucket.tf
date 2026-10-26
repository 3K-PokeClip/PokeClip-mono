# 채팅 원본 버킷(POK-306 · kty 2026-10-26) — chat-collector 가 받은 채팅의 원본
# 을 1분 창 JSON Lines 로 올리고(S3ArchiveUploader), 탈퇴 정리가 채널 접두로
# 지운다(S3ArchivePurge — 목록 뒤 DeleteObjects). 키는 모두 chat/ 아래다
# (ArchiveKey — chat/{PLATFORM}/{channelId}/{날짜}/{시}/… · 옛 경로
# chat/{channelId}/…). 공개 차단 · ACL 끔 · SSE-S3 · chat/ 만료 60일 · 미완료
# 멀티파트 정리를 둔다(clips 버킷과 같은 꼴 — 만료 범위만 다르다).
#
# 만료 60일: 처리방침 3절의 「받은 지 60일」 보관이다. landing/README.md 가
# 운영 창고를 만들 때 채팅 원본(chat/)에 같은 규칙을 걸라고 적어 둔다.
#
# 두지 않는 것:
#   - 버전 관리: 결정 50 · 백업 없음(services README). 지운 원본이 이전
#     버전으로 남으면 처리방침의 삭제가 아니다.
#   - 버킷 정책: TLS 거부 정책은 런타임 2단 버킷 정책 설계 대상이다(K8).
#   - CORS: 브라우저가 읽지 않는다.
#   - 쓰기 · 지우기 정책: chat-collector 작업 역할에 platform 이 붙인다.

variable "chat_archive_bucket_name" {
  description = "채팅 원본 버킷 이름(전역 유일 — 접두 pokeclip-prod-)."
  type        = string
  default     = "pokeclip-prod-chat-archive-apne2"
}

# force_destroy = false — 객체가 남아 있으면 버킷 삭제가 실패한다.
resource "aws_s3_bucket" "chat_archive" {
  bucket        = var.chat_archive_bucket_name
  force_destroy = false
}

resource "aws_s3_bucket_public_access_block" "chat_archive" {
  bucket = aws_s3_bucket.chat_archive.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACL 을 끄고 버킷 소유자가 모든 객체를 소유한다.
resource "aws_s3_bucket_ownership_controls" "chat_archive" {
  bucket = aws_s3_bucket.chat_archive.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "chat_archive" {
  bucket = aws_s3_bucket.chat_archive.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# chat/ 아래 객체는 마지막 수정 60일 뒤 만료된다. 원본 파일은 1분 창마다 한 번
# 쓰고 다시 쓰지 않아 마지막 수정이 곧 받은 때다. 끝나지 않은 멀티파트 업로드는
# 버킷 전체에서 7일 뒤 중단한다.
resource "aws_s3_bucket_lifecycle_configuration" "chat_archive" {
  bucket = aws_s3_bucket.chat_archive.id

  rule {
    id     = "expire-chat-after-60-days"
    status = "Enabled"

    filter {
      prefix = "chat/"
    }

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

output "chat_archive_bucket_name" {
  description = "채팅 원본 버킷 이름 — chat-collector 의 S3_BUCKET(app_env 에도 있음)."
  value       = aws_s3_bucket.chat_archive.bucket
}

output "chat_archive_bucket_arn" {
  description = "채팅 원본 버킷 ARN — platform 의 chat-collector 작업 역할 정책(PutObject · DeleteObject 는 /*, ListBucket 은 버킷)."
  value       = aws_s3_bucket.chat_archive.arn
}
