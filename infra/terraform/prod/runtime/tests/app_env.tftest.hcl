# 출력 app_env(POK-309 · POK-408 · POK-429 · 설계 3절 · 1단-다 D7 · D13) —
# 서비스별 비밀 아닌 env 의 키 집합과 값이 RDS · 계약9 큐 · 일 큐 · 버킷 ·
# Media 서브넷 대역을 가리키는지, clip 일 스위치의 거부 규칙, platform 의 런타임
# 계약 검사를 통과하는지 단언한다(CONTRACT-runtime.md 「런타임 1단 → platform」
# 행). 주소 · URL 은 mock 이 만드는 가짜 값이다. override_data 가 network 원격
# 상태와 서브넷 조회를 대신하므로 S3 · EC2 를 읽지 않는다(AWS 호출 0).
#
# 아래 run 「passes_platform_app_env_contract_checks」 는 platform
# remote_state.tf 의 runtime_contract(app_env 이름 꼴 · 비밀 낱말 · 값 자격 증명 ·
# 값 운반 이름 · 값 운반 꼴)와 같은 규칙이다 — 바꾸면 두 곳을 함께 고친다.
# platform 변수 app_env_non_secret_names 로 푼 이름은 이 시험이 모른다(기본 빈
# 목록 기준) — 런타임에 그런 이름을 더하면 계약 예외로 올리거나 이 시험 예외에
# 같이 적는다.

mock_provider "aws" {
  source = "./tests/mocks"
}

# auth_oauth_apps 는 기본값이 없다(auth_settings.tf — tfvars 필수). 이 파일의
# 모든 run 에 흐르는 예시 값이다(run 안의 variables 가 덮는 변수만 바뀐다).
variables {
  auth_oauth_apps = {
    google  = { client_id = "example-google-client-id", redirect_uri = "https://app.pokeclip.com/auth/callback" }
    chzzk   = { client_id = "example-chzzk-client-id", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
    youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
    soop    = { client_id = "example-soop-client-id", redirect_uri = "https://app.pokeclip.com/oauth/soop/callback" }
  }
}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id                  = "vpc-0example"
      private_subnet_ids      = ["subnet-0examplea", "subnet-0examplec"]
      media_az                = "ap-northeast-2c"
      media_private_subnet_id = "subnet-0examplec"
    }
  }
}

# 문서용 대역(RFC 5737)이다. mock 기본값(192.0.2.0/24)과 달라서 값이 이
# 서브넷이 아닌 곳에서 오면 아래 단언이 깨진다.
override_data {
  target = data.aws_subnet.media_private
  values = {
    cidr_block = "198.51.100.0/24"
  }
}

# env 이름은 각 서비스 application.yml 실물이다. 이름을 더하면 이 집합도
# 함께 고친다(POSTGRES_USER 는 platform 이 app_db_users 로 넣는다).
run "lists_env_names_per_service" {
  command = apply

  assert {
    condition = toset(keys(output.app_env)) == toset([
      "auth", "clip", "chat-collector", "chat-detector", "render", "upload",
    ])
    error_message = "app_env 의 서비스 집합이 auth · clip · chat-collector · chat-detector · render · upload 와 다르다."
  }

  assert {
    condition = toset(keys(output.app_env["auth"])) == toset([
      "DB_HOST", "DB_PORT", "POSTGRES_DB",
      "GOOGLE_CLIENT_ID", "GOOGLE_REDIRECT_URI",
      "CHZZK_CLIENT_ID", "CHZZK_REDIRECT_URI",
      "YOUTUBE_CLIENT_ID", "YOUTUBE_REDIRECT_URI",
      "SOOP_CLIENT_ID", "SOOP_REDIRECT_URI",
      "PROFILE_PHOTO_S3_BUCKET", "PROFILE_PHOTO_BASE_URL",
      "STREAM_KEY_RESOLVE_ALLOWED_CIDRS",
    ])
    error_message = "auth 의 env 이름 집합이 DB 셋 · OAuth 네 덩어리의 CLIENT_ID · REDIRECT_URI 여덟 · 프로필 사진 둘 · STREAM_KEY_RESOLVE_ALLOWED_CIDRS 와 다르다(STREAM_KEY_SECRET_REF_PREFIX 는 platform 이 넣는다)."
  }

  assert {
    condition = toset(keys(output.app_env["clip"])) == toset([
      "DB_HOST", "DB_PORT", "POSTGRES_DB", "BROADCAST_QUEUE_URL",
      "BROADCAST_INTAKE_ENABLED",
      "RENDER_ENABLED", "RENDER_QUEUE_URL", "RENDER_DLQ_URL",
      "UPLOAD_ENABLED", "UPLOAD_QUEUE_URL", "UPLOAD_DLQ_URL",
      "THUMBNAIL_ENABLED", "THUMBNAIL_QUEUE_URL",
      "CLIPS_BUCKET", "SEGMENT_BUCKET",
    ])
    error_message = "clip 의 env 이름 집합이 DB 셋 · 계약9 둘 · 일 스위치 셋 · 일 큐 주소 다섯 · 창고 이름 둘과 다르다(BROADCAST_REAPER_ENABLED 없음 — K6)."
  }

  assert {
    condition = toset(keys(output.app_env["chat-collector"])) == toset([
      "DB_HOST", "DB_PORT", "POSTGRES_DB", "BROADCAST_QUEUE_URL",
      "CHZZK_CLIENT_ID", "SOOP_CLIENT_ID",
    ])
    error_message = "chat-collector 의 env 이름 집합이 DB 셋 · BROADCAST_QUEUE_URL · CHZZK_CLIENT_ID · SOOP_CLIENT_ID 와 다르다(REDIRECT_URI 는 auth 만)."
  }

  assert {
    condition = toset(keys(output.app_env["chat-detector"])) == toset([
      "DB_HOST", "DB_PORT", "POSTGRES_DB",
    ])
    error_message = "chat-detector 의 env 이름 집합이 DB_HOST · DB_PORT · POSTGRES_DB 와 다르다."
  }

  # 일꾼은 DB 를 읽지 않는다 — DB 키가 없다. 주소 CLIP_BASE_URL · AUTH_BASE_URL 은
  # 배치 루트가 넣는다(D7).
  assert {
    condition = toset(keys(output.app_env["render"])) == toset([
      "RENDER_QUEUE_URL", "THUMBNAIL_QUEUE_URL",
      "RENDER_VISIBILITY_TIMEOUT", "THUMBNAIL_VISIBILITY_TIMEOUT",
    ])
    error_message = "render 의 env 이름 집합이 큐 주소 둘 · 숨김 둘과 다르다."
  }

  assert {
    condition = toset(keys(output.app_env["upload"])) == toset([
      "UPLOAD_QUEUE_URL", "UPLOAD_VISIBILITY_TIMEOUT",
    ])
    error_message = "upload 의 env 이름 집합이 UPLOAD_QUEUE_URL · UPLOAD_VISIBILITY_TIMEOUT 과 다르다."
  }
}

# 주소는 계산 속성이라 apply 로 돌려 mock 이 값을 만들게 한다. endpoint 는
# 호스트:포트라 DB_HOST 가 될 수 없다 — 둘이 다른 값인지 먼저 본다. DB 를 쓰는
# 서비스 넷(app_db_users 의 키)만 돈다 — 일꾼에 DB 키가 없음은 키 집합 run 이
# 강제한다.
run "points_db_env_at_rds_instance" {
  command = apply

  assert {
    condition     = aws_db_instance.this.address != aws_db_instance.this.endpoint
    error_message = "mock 이 address 와 endpoint 에 같은 값을 줬다 — DB_HOST 단언이 의미를 잃는다."
  }

  assert {
    condition = alltrue([
      for service in keys(output.app_db_users) :
      output.app_env[service]["DB_HOST"] == aws_db_instance.this.address
    ])
    error_message = "DB_HOST 가 RDS address(호스트만)가 아니다."
  }

  # 태스크 env 값은 문자열이다 — 숫자 5432 가 아니라 문자열 "5432".
  assert {
    condition = alltrue([
      for service in keys(output.app_db_users) : output.app_env[service]["DB_PORT"] == "5432"
    ])
    error_message = "DB_PORT 가 문자열 \"5432\" 가 아니다."
  }

  assert {
    condition = alltrue([
      for service in keys(output.app_db_users) :
      output.app_env[service]["POSTGRES_DB"] == aws_db_instance.this.db_name &&
      output.app_env[service]["POSTGRES_DB"] == "pokeclip"
    ])
    error_message = "POSTGRES_DB 가 RDS DB 이름 pokeclip 이 아니다."
  }
}

# 방송 편지는 팬아웃이라 두 서버가 각자 자기 큐를 받는다 — 같은 큐를 가리키면
# 두 서버가 편지를 서로 뺏는다(계약9 1절).
run "gives_each_consumer_its_own_queue_url" {
  command = apply

  assert {
    condition     = output.app_env["clip"]["BROADCAST_QUEUE_URL"] == aws_sqs_queue.broadcast["clip"].url
    error_message = "clip 의 BROADCAST_QUEUE_URL 이 clip 큐 URL 이 아니다."
  }

  assert {
    condition     = output.app_env["chat-collector"]["BROADCAST_QUEUE_URL"] == aws_sqs_queue.broadcast["chat"].url
    error_message = "chat-collector 의 BROADCAST_QUEUE_URL 이 chat 큐 URL 이 아니다."
  }

  assert {
    condition     = output.app_env["clip"]["BROADCAST_QUEUE_URL"] != output.app_env["chat-collector"]["BROADCAST_QUEUE_URL"]
    error_message = "clip 과 chat-collector 가 같은 큐 URL 을 받는다."
  }
}

# auth 의 프로필 사진 창고는 이 루트의 사진 버킷이다(D5 · D7). 주소는 변수
# profile_photo_base_url 이다(K7).
run "points_auth_profile_photo_env_at_bucket" {
  command = apply

  assert {
    condition     = output.app_env["auth"]["PROFILE_PHOTO_S3_BUCKET"] == aws_s3_bucket.profile_photo.bucket
    error_message = "auth 의 PROFILE_PHOTO_S3_BUCKET 이 프로필 사진 버킷 이름이 아니다."
  }

  assert {
    condition     = output.app_env["auth"]["PROFILE_PHOTO_BASE_URL"] == var.profile_photo_base_url
    error_message = "auth 의 PROFILE_PHOTO_BASE_URL 이 변수 profile_photo_base_url 이 아니다."
  }
}

# resolve 전용 창구의 출발지는 Media 프라이빗 서브넷 하나다(POK-429 · K3).
# auth 는 전용 토큰이 있는데 이 대역이 비면 부팅을 거부한다. 대역은 앱이
# IPv4 CIDR 로 읽는다. 이름이 platform 의 비밀 낱말 검사를 통과하는지는
# platform 시험(services.tftest.hcl 의 계약 이름 오탐 run)이 본다.
run "allows_resolve_from_media_private_subnet_only" {
  command = apply

  assert {
    condition     = data.aws_subnet.media_private.id == local.media_private_subnet_id
    error_message = "서브넷 조회가 network 출력 media_private_subnet_id 를 읽지 않는다."
  }

  assert {
    condition     = output.app_env["auth"]["STREAM_KEY_RESOLVE_ALLOWED_CIDRS"] == "198.51.100.0/24"
    error_message = "auth 의 STREAM_KEY_RESOLVE_ALLOWED_CIDRS 가 Media 프라이빗 서브넷의 cidr_block 이 아니다."
  }

  assert {
    condition     = can(cidrhost(output.app_env["auth"]["STREAM_KEY_RESOLVE_ALLOWED_CIDRS"], 0)) && can(regex("^[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+/[0-9]+$", output.app_env["auth"]["STREAM_KEY_RESOLVE_ALLOWED_CIDRS"]))
    error_message = "auth 의 STREAM_KEY_RESOLVE_ALLOWED_CIDRS 가 IPv4 CIDR 하나가 아니다."
  }
}

# chat-collector 는 auth 와 같은 치지직 · SOOP 앱으로 방송 정보 목록(치지직)과
# SOOP 채팅 SDK 를 연다(POK-306 · 앱 비밀은 app_secret_arns). 값은 auth 와 같은
# 변수 auth_oauth_apps 에서 온다 — 따로 두면 한쪽만 바꾼 배포가 생긴다. auth 의
# 값도 그대로인지 함께 본다.
run "gives_chat_collector_the_auth_app_ids" {
  command = plan

  assert {
    condition = (
      output.app_env["chat-collector"]["CHZZK_CLIENT_ID"] == var.auth_oauth_apps.chzzk.client_id &&
      output.app_env["chat-collector"]["SOOP_CLIENT_ID"] == var.auth_oauth_apps.soop.client_id
    )
    error_message = "chat-collector 의 CHZZK_CLIENT_ID · SOOP_CLIENT_ID 가 auth_oauth_apps 의 chzzk · soop client_id 가 아니다."
  }

  assert {
    condition = (
      output.app_env["auth"]["CHZZK_CLIENT_ID"] == var.auth_oauth_apps.chzzk.client_id &&
      output.app_env["auth"]["SOOP_CLIENT_ID"] == var.auth_oauth_apps.soop.client_id
    )
    error_message = "auth 의 CHZZK_CLIENT_ID · SOOP_CLIENT_ID 가 auth_oauth_apps 의 chzzk · soop client_id 가 아니다."
  }
}

# 큐 수신 스위치(서버 기본 false)는 clip 만 런타임이 켠다(kty 2026-10-24).
# 빠지거나 false 면 clip 은 뜨지만 큐를 읽지 않아 편지가 보관 기간 뒤
# 사라진다. 태스크 env 값은 문자열이라 불리언 true 가 아니라 "true" 다.
# chat-collector 는 배치(POK-306)가 AUTH_BASE_URL 과 함께 켠다.
run "turns_on_broadcast_intake_for_clip_only" {
  command = apply

  assert {
    condition     = lookup(output.app_env["clip"], "BROADCAST_INTAKE_ENABLED", null) == "true"
    error_message = "clip 의 BROADCAST_INTAKE_ENABLED 가 문자열 \"true\" 가 아니다."
  }

  assert {
    condition     = !contains(keys(output.app_env["chat-collector"]), "BROADCAST_INTAKE_ENABLED")
    error_message = "chat-collector 에 BROADCAST_INTAKE_ENABLED 가 있다 — 배치(POK-306)가 정한다."
  }
}

# 일 큐 주소는 이 루트의 큐 자원이다. 일꾼과 clip 이 같은 큐를 가리킨다.
# segment 창고 = rewind 버킷(D4 — 사이드카가 조각을 rewind 에 올린다).
run "points_job_env_at_queues_and_buckets" {
  command = apply

  assert {
    condition = (
      output.app_env["clip"]["RENDER_QUEUE_URL"] == aws_sqs_queue.job["render"].url &&
      output.app_env["clip"]["RENDER_DLQ_URL"] == aws_sqs_queue.job_dlq["render"].url &&
      output.app_env["clip"]["UPLOAD_QUEUE_URL"] == aws_sqs_queue.job["upload"].url &&
      output.app_env["clip"]["UPLOAD_DLQ_URL"] == aws_sqs_queue.job_dlq["upload"].url &&
      output.app_env["clip"]["THUMBNAIL_QUEUE_URL"] == aws_sqs_queue.job["thumbnail"].url
    )
    error_message = "clip 의 일 큐 주소 다섯이 같은 일의 원 큐 · DLQ url 이 아니다."
  }

  assert {
    condition = (
      output.app_env["render"]["RENDER_QUEUE_URL"] == aws_sqs_queue.job["render"].url &&
      output.app_env["render"]["THUMBNAIL_QUEUE_URL"] == aws_sqs_queue.job["thumbnail"].url &&
      output.app_env["upload"]["UPLOAD_QUEUE_URL"] == aws_sqs_queue.job["upload"].url
    )
    error_message = "일꾼의 큐 주소가 같은 일의 원 큐 url 이 아니다."
  }

  assert {
    condition = (
      output.app_env["clip"]["CLIPS_BUCKET"] == aws_s3_bucket.clips.bucket &&
      output.app_env["clip"]["SEGMENT_BUCKET"] == aws_s3_bucket.rewind.bucket
    )
    error_message = "clip 의 CLIPS_BUCKET · SEGMENT_BUCKET 이 clips 버킷 · rewind 버킷 이름이 아니다(D4)."
  }
}

# 일꾼의 숨김 env 는 큐의 숨김 시간과 같은 상수에서 만든다(일꾼 README 「큐
# 설정과 같아야 한다」). Spring Duration 꼴이라 초 뒤에 s 를 붙인다.
run "derives_worker_visibility_env_from_queue_constants" {
  command = apply

  assert {
    condition = (
      output.app_env["render"]["RENDER_VISIBILITY_TIMEOUT"] == "${aws_sqs_queue.job["render"].visibility_timeout_seconds}s" &&
      output.app_env["render"]["THUMBNAIL_VISIBILITY_TIMEOUT"] == "${aws_sqs_queue.job["thumbnail"].visibility_timeout_seconds}s" &&
      output.app_env["upload"]["UPLOAD_VISIBILITY_TIMEOUT"] == "${aws_sqs_queue.job["upload"].visibility_timeout_seconds}s"
    )
    error_message = "일꾼의 숨김 env 가 같은 일 큐의 숨김 시간 + s 가 아니다."
  }

  assert {
    condition = (
      output.app_env["render"]["RENDER_VISIBILITY_TIMEOUT"] == "600s" &&
      output.app_env["render"]["THUMBNAIL_VISIBILITY_TIMEOUT"] == "120s" &&
      output.app_env["upload"]["UPLOAD_VISIBILITY_TIMEOUT"] == "900s"
    )
    error_message = "일꾼의 숨김 env 가 600s · 120s · 900s 가 아니다."
  }
}

# 기본은 셋 다 켬이다(K5). clip 은 havingValue = "true" 로 읽어 문자열이다.
run "turns_on_all_clip_jobs_by_default" {
  command = plan

  assert {
    condition = (
      output.app_env["clip"]["RENDER_ENABLED"] == "true" &&
      output.app_env["clip"]["UPLOAD_ENABLED"] == "true" &&
      output.app_env["clip"]["THUMBNAIL_ENABLED"] == "true"
    )
    error_message = "clip 의 일 스위치 기본값이 셋 다 문자열 \"true\" 가 아니다."
  }
}

# 한쪽만 끈 배포는 통과한다 — 남은 쪽이 창고 지우기 빈을 만든다. 끈 쪽의 큐
# 주소와 창고 이름은 그대로 낸다(D7 · D13 — 탈퇴 정리가 두 이름으로 지운다).
run "turns_off_render_only" {
  command = plan

  variables {
    clip_job_features = { render = false, upload = true, thumbnail = true }
  }

  assert {
    condition = (
      output.app_env["clip"]["RENDER_ENABLED"] == "false" &&
      output.app_env["clip"]["UPLOAD_ENABLED"] == "true" &&
      output.app_env["clip"]["THUMBNAIL_ENABLED"] == "true"
    )
    error_message = "render 만 껐는데 스위치가 false · true · true 가 아니다."
  }

  assert {
    condition = alltrue([
      for name in ["RENDER_QUEUE_URL", "RENDER_DLQ_URL", "CLIPS_BUCKET", "SEGMENT_BUCKET"] :
      contains(keys(output.app_env["clip"]), name)
    ])
    error_message = "render 를 끄니 RENDER 큐 주소 · 창고 이름이 빠졌다 — 늘 낸다(D7)."
  }

  # 키만 있고 값이 빈 문자열이면 위 단언은 통과한다. 탈퇴 정리는 render 가
  # 꺼져도 segment 창고 이름으로 지우므로 값까지 rewind 버킷과 맞춘다(D7 · D13).
  assert {
    condition     = output.app_env["clip"]["SEGMENT_BUCKET"] == aws_s3_bucket.rewind.bucket
    error_message = "render 를 끄니 SEGMENT_BUCKET 이 rewind 버킷 이름이 아니다 — 빈 값 회귀(D7)."
  }
}

run "turns_off_upload_only" {
  command = plan

  variables {
    clip_job_features = { render = true, upload = false, thumbnail = true }
  }

  assert {
    condition = (
      output.app_env["clip"]["RENDER_ENABLED"] == "true" &&
      output.app_env["clip"]["UPLOAD_ENABLED"] == "false" &&
      output.app_env["clip"]["THUMBNAIL_ENABLED"] == "true"
    )
    error_message = "upload 만 껐는데 스위치가 true · false · true 가 아니다."
  }

  assert {
    condition = alltrue([
      for name in ["UPLOAD_QUEUE_URL", "UPLOAD_DLQ_URL", "CLIPS_BUCKET", "SEGMENT_BUCKET"] :
      contains(keys(output.app_env["clip"]), name)
    ])
    error_message = "upload 를 끄니 UPLOAD 큐 주소 · 창고 이름이 빠졌다 — 늘 낸다(D7)."
  }
}

run "turns_off_thumbnail_only" {
  command = plan

  variables {
    clip_job_features = { render = true, upload = true, thumbnail = false }
  }

  assert {
    condition = (
      output.app_env["clip"]["RENDER_ENABLED"] == "true" &&
      output.app_env["clip"]["UPLOAD_ENABLED"] == "true" &&
      output.app_env["clip"]["THUMBNAIL_ENABLED"] == "false"
    )
    error_message = "thumbnail 만 껐는데 스위치가 true · true · false 가 아니다."
  }

  assert {
    condition     = contains(keys(output.app_env["clip"]), "THUMBNAIL_QUEUE_URL")
    error_message = "thumbnail 을 끄니 THUMBNAIL_QUEUE_URL 이 빠졌다 — 늘 낸다(D7)."
  }
}

# render · upload 를 둘 다 끄면 탈퇴 정리가 rewind 녹화 조각을 지우지 않은 채
# 완료로 닫는다(PurgeConfiguration.java — 창고 지우기 빈이 없음). plan 에서
# 멈춘다(D13).
run "rejects_render_and_upload_both_off" {
  command = plan

  variables {
    clip_job_features = { render = false, upload = false, thumbnail = true }
  }

  expect_failures = [var.clip_job_features]
}

# platform remote_state.tf runtime_contract 의 app_env 검사 다섯을 서비스 여섯의
# 모든 이름 · 모든 값에 그대로 돌린다(머리 주석 — 바꾸면 두 곳을 함께 고친다).
run "passes_platform_app_env_contract_checks" {
  command = apply

  # 이름 꼴
  assert {
    condition = alltrue(flatten([
      for env in values(output.app_env) : [
        for name in keys(env) : can(regex("^[A-Z][A-Z0-9_]*$", name))
      ]
    ]))
    error_message = "app_env 에 대문자 · 숫자 · _ 꼴(^[A-Z][A-Z0-9_]*$)이 아닌 이름이 있다 — platform 이 거부한다."
  }

  # 비밀 낱말 — 계약 예외 다섯은 platform 과 같다.
  assert {
    condition = alltrue(flatten([
      for env in values(output.app_env) : [
        for name in keys(env) :
        contains([
          "STREAM_KEY_RESOLVE_ALLOWED_CIDRS",
          "STREAM_KEY_SECRET_STORE_TYPE",
          "STREAM_KEY_SECRET_RETIRE_FROM",
          "STREAM_KEY_SECRET_REF_PREFIX",
          "CLOUDFRONT_KEY_PAIR_ID",
        ], name) ||
        !anytrue([
          for word in ["PASSWORD", "PASSWD", "PASSPHRASE", "SECRET", "TOKEN", "KEY", "PEM", "CREDENTIAL"] :
          strcontains(upper(name), word)
        ])
      ]
    ]))
    error_message = "app_env 이름에 비밀 낱말(PASSWORD · PASSWD · PASSPHRASE · SECRET · TOKEN · KEY · PEM · CREDENTIAL)이 있다 — platform 이 거부한다."
  }

  # 값의 URL 자격 증명(scheme://user:pass@)
  assert {
    condition = alltrue(flatten([
      for env in values(output.app_env) : [
        for value in values(env) : !anytrue([
          for i, seg in split("://", value) :
          i > 0 && can(regex("^[^/@]*:[^/@]*@", seg))
        ])
      ]
    ]))
    error_message = "app_env 값에 URL 자격 증명이 있다 — platform 이 거부한다."
  }

  # 값 운반 이름
  assert {
    condition = alltrue(flatten([
      for env in values(output.app_env) : [
        for name in keys(env) : !contains([
          "SPRING_APPLICATION_JSON",
          "JAVA_TOOL_OPTIONS",
          "JDK_JAVA_OPTIONS",
          "JAVA_OPTS",
        ], name)
      ]
    ]))
    error_message = "app_env 에 값 운반 이름이 있다 — platform 이 거부한다."
  }

  # 값 운반 꼴(쿼리 · 프래그먼트 비밀 키 · -D 속성 · 콜론 없는 userinfo)
  assert {
    condition = alltrue(flatten([
      for env in values(output.app_env) : [
        for value in values(env) : !(
          can(regex("(?i)[?&;#][^=&;#?]*(passw|pwd|secret|token)[^=&;#?]*=", value)) ||
          can(regex("(^|[\\s\"'])-D[^ =]*(?i:secret|password|token)", value)) ||
          anytrue([
            for i, seg in split("://", value) :
            i > 0 && can(regex("^[^/@:]+@", seg))
          ])
        )
      ]
    ]))
    error_message = "app_env 값에 비밀을 싣는 꼴이 있다 — platform 이 거부한다."
  }
}
