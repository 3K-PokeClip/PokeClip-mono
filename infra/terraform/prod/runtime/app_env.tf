# 서비스별 비밀 아닌 env(POK-309 · 설계 3절) — platform 이 태스크 env 로 넣는다
# (CONTRACT-runtime.md 「런타임 1단 → platform」 행). 비밀은 app_secret_arns 에
# 있다. env 이름은 각 서비스 application.yml 실물이고, 1단-나가 POSTGRES_USER
# 등을 더한다.

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
# 큐 수신 스위치 BROADCAST_INTAKE_ENABLED(두 서버 기본 false)는 여기 두지
# 않는다. 켜면 auth 내부 주소 AUTH_BASE_URL 도 함께 필요한데 그 값은 서비스를
# 띄우는 2단 platform 이 안다. 그래서 스위치와 주소는 platform 이 같이 넣는다
# (kty 2026-10-23). 둘 다 빠지면 서버는 뜨지만 큐를 읽지 않아 편지가 보관
# 기간 뒤 사라진다 — 2단 리뷰의 확인 항목이다.
output "app_env" {
  description = "서비스 → env 이름 → 값(비밀 아님) — platform 의 ecs_service.environment."
  value = {
    auth = merge(local.app_db_env, {
      STREAM_KEY_SECRET_REF_PREFIX = "pokeclip/prod/stream-key/"
    })
    clip = merge(local.app_db_env, {
      BROADCAST_QUEUE_URL = aws_sqs_queue.broadcast["clip"].url
    })
    "chat-collector" = merge(local.app_db_env, {
      BROADCAST_QUEUE_URL = aws_sqs_queue.broadcast["chat"].url
    })
    "chat-detector" = local.app_db_env
  }
}
