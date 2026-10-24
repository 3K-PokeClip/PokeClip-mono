# 서비스별 비밀 아닌 env(POK-309 · POK-408 · 설계 3절 · 1단-다 D7) — platform 이
# auth · clip 을, 배치 루트가 일꾼 render · upload 를 태스크 env 로 넣는다
# (CONTRACT-runtime.md 「런타임 1단 → platform」 행). 비밀은 app_secret_arns 에
# 있다. env 이름은 각 서비스 application.yml 실물이다. POSTGRES_USER 는
# platform 이 app_db_users 로 넣는다. platform 이 정하는 이름(스트림 키 접두
# STREAM_KEY_SECRET_REF_PREFIX 등)은 여기 넣지 않는다 — clip 의 큐 수신 · 일
# 스위치는 platform 이 넣지 않으므로 여기서 준다.

# clip 일 스위치(K5 · D13). 사고 때(예: 유튜브 할당량) 코드 PR 없이 tfvars 로
# 한쪽을 끈다. 둘 다 끄는 값은 plan 에서 멈춘다 — clip 의 창고 지우기 빈은
# render 또는 upload 가 켜져야 생기고(PurgeConfiguration.java), 빈이 없으면 탈퇴
# 정리가 S3 를 지우지 않은 채 명부를 비우고 완료로 닫는다. thumbnail 은 그
# 조건에 없다. 한계: 코드 되돌리기로 env 가 빠지는 길(yml 기본값 false)은 이
# 검사가 막지 못한다 — runbooks/runtime-1a.md 의 되돌리기 절차가 막는다.
variable "clip_job_features" {
  description = "clip 일 스위치 — render · upload · thumbnail 주문을 켤지. render · upload 를 둘 다 끌 수는 없다."
  type = object({
    render    = bool
    upload    = bool
    thumbnail = bool
  })
  default = {
    render    = true
    upload    = true
    thumbnail = true
  }

  validation {
    condition     = var.clip_job_features.render || var.clip_job_features.upload
    error_message = "clip_job_features 의 render · upload 를 둘 다 끌 수 없다 — clip 의 창고 지우기 빈이 사라져 탈퇴 정리가 rewind 녹화 조각을 지우지 않은 채 완료되고, 그 조각은 영구히 남는다."
  }
}

locals {
  # 네 서버가 같은 DB 에 붙는다. 태스크 env 값은 문자열이라 포트도 문자열이다.
  app_db_env = {
    DB_HOST     = aws_db_instance.this.address
    DB_PORT     = tostring(aws_db_instance.this.port)
    POSTGRES_DB = aws_db_instance.this.db_name
  }
}

# 방송 편지는 팬아웃이라 clip 과 chat-collector 가 각자 자기 큐를 받는다 — 같은
# 큐를 가리키면 두 서버가 편지를 서로 뺏는다.
#
# 큐 수신 스위치 BROADCAST_INTAKE_ENABLED(두 서버 기본 false)는 clip 만
# 여기서 켠다(kty 2026-10-24). 값은 태스크 env 라 문자열 "true" 다. 스위치가
# 빠지면 서버는 뜨지만 큐를 읽지 않아 편지가 보관 기간 뒤 사라진다. auth 내부
# 주소 AUTH_BASE_URL 은 서비스를 띄우는 platform 이 넣는다. 주소가 빠지면 clip
# 은 부팅을 거부한다. 켜면 auth 내부 호출에 INTERNAL_API_TOKEN 도 쓴다(그릇은
# app_secret_arns).
output "app_env" {
  description = "서비스 → env 이름 → 값(비밀 아님) — platform(auth · clip 등) · 배치 루트(render · upload)의 ecs_service.environment."
  value = {
    # OAuth 설정 · 프로필 사진 env 는 auth_settings.tf 의 locals 다.
    auth = merge(local.app_db_env, local.auth_settings_env)
    # 스위치는 clip 이 havingValue = "true" 로 읽어 문자열이다. 큐 주소와 창고
    # 이름은 스위치와 무관하게 늘 낸다 — 한쪽이 꺼진 배포의 탈퇴 정리가 두
    # 창고 이름으로 지운다(D7). segment 창고는 rewind 버킷이다(D4 — 사이드카가
    # 조각을 rewind 에 올림).
    clip = merge(local.app_db_env, {
      BROADCAST_QUEUE_URL      = aws_sqs_queue.broadcast["clip"].url
      BROADCAST_INTAKE_ENABLED = "true"
      RENDER_ENABLED           = tostring(var.clip_job_features.render)
      RENDER_QUEUE_URL         = aws_sqs_queue.job["render"].url
      RENDER_DLQ_URL           = aws_sqs_queue.job_dlq["render"].url
      UPLOAD_ENABLED           = tostring(var.clip_job_features.upload)
      UPLOAD_QUEUE_URL         = aws_sqs_queue.job["upload"].url
      UPLOAD_DLQ_URL           = aws_sqs_queue.job_dlq["upload"].url
      THUMBNAIL_ENABLED        = tostring(var.clip_job_features.thumbnail)
      THUMBNAIL_QUEUE_URL      = aws_sqs_queue.job["thumbnail"].url
      CLIPS_BUCKET             = aws_s3_bucket.clips.bucket
      SEGMENT_BUCKET           = aws_s3_bucket.rewind.bucket
    })
    # 스위치는 배치(POK-306)가 AUTH_BASE_URL · INTERNAL_API_TOKEN 과 함께
    # 정한다 — AUTH_BASE_URL 이 없으면 localhost 로 떠서 틀린 주소로 돈다.
    "chat-collector" = merge(local.app_db_env, {
      BROADCAST_QUEUE_URL = aws_sqs_queue.broadcast["chat"].url
    })
    "chat-detector" = local.app_db_env
    # 일꾼은 DB 를 읽지 않는다. 숨김은 큐의 숨김 시간과 같은 상수에서 만든다
    # (job_queues.tf — 일꾼 README 「큐 설정과 같아야 한다」). Spring Duration
    # 꼴이라 초 뒤에 s 를 붙인다. CLIP_BASE_URL · AUTH_BASE_URL 은 배치 루트가
    # 넣는다(Cloud Map 이름).
    render = {
      RENDER_QUEUE_URL             = aws_sqs_queue.job["render"].url
      THUMBNAIL_QUEUE_URL          = aws_sqs_queue.job["thumbnail"].url
      RENDER_VISIBILITY_TIMEOUT    = "${local.job_queue_visibility_timeout_seconds.render}s"
      THUMBNAIL_VISIBILITY_TIMEOUT = "${local.job_queue_visibility_timeout_seconds.thumbnail}s"
    }
    upload = {
      UPLOAD_QUEUE_URL          = aws_sqs_queue.job["upload"].url
      UPLOAD_VISIBILITY_TIMEOUT = "${local.job_queue_visibility_timeout_seconds.upload}s"
    }
  }
}
