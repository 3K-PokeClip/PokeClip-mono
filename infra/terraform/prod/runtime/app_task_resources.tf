# 작업 역할 정책 대상 지도(POK-408 · 설계 D10 · K1 · K3) — platform 이 auth · clip
# 작업 역할 정책을, 배치 루트가 일꾼 render · upload 작업 역할 정책을 이 ARN 으로
# 만든다(CONTRACT-runtime.md 「작업 역할 대상」). 큐 · 버킷 · 방송 큐를 함께
# 모으므로 자원 파일이 아니라 여기 둔다.
#
# 키는 platform 의 task_resource_keys(prod/platform remote_state.tf)와 같은
# 이름이다 — platform 은 모르는 키를 거부하고, 값은 와일드카드 없는 S3 버킷 ·
# SQS 큐 ARN 꼴만 받는다. 썸네일 DLQ 키는 내지 않는다(K3 — clip 은 썸네일
# DLQ 를 읽지 않음). segment 창고는 rewind 버킷이다(D4).

output "app_task_resource_arns" {
  description = "서비스 → 키 → ARN(와일드카드 없음) — platform(auth · clip) · 배치 루트(render · upload) 작업 역할 정책 Resource."
  value = {
    auth = {
      profile_photo_bucket_arn = aws_s3_bucket.profile_photo.arn
    }
    clip = {
      broadcast_queue_arn = aws_sqs_queue.broadcast["clip"].arn
      render_queue_arn    = aws_sqs_queue.job["render"].arn
      render_dlq_arn      = aws_sqs_queue.job_dlq["render"].arn
      upload_queue_arn    = aws_sqs_queue.job["upload"].arn
      upload_dlq_arn      = aws_sqs_queue.job_dlq["upload"].arn
      thumbnail_queue_arn = aws_sqs_queue.job["thumbnail"].arn
      clips_bucket_arn    = aws_s3_bucket.clips.arn
      segment_bucket_arn  = aws_s3_bucket.rewind.arn
    }
    render = {
      render_queue_arn    = aws_sqs_queue.job["render"].arn
      thumbnail_queue_arn = aws_sqs_queue.job["thumbnail"].arn
      segment_bucket_arn  = aws_s3_bucket.rewind.arn
      clips_bucket_arn    = aws_s3_bucket.clips.arn
    }
    upload = {
      upload_queue_arn = aws_sqs_queue.job["upload"].arn
      clips_bucket_arn = aws_s3_bucket.clips.arn
    }
  }
}
