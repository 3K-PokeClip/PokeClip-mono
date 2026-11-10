# 계약9 발행 입력(broadcast_topic_arn · lifecycle_publish_streams)의 검증을
# 단언한다(O8a 당김 계획 PR-A 시험 T-A · T-C · T-M).
#
# 주제 ARN 판정은 사이드카 media/internal/config/lifecycle.go 의 loadLifecycle
# (arn.Parse + 다섯 조건)과 같아야 한다 — Terraform 이 통과시킨 값을 사이드카가
# 기동 거부하지 않게. 파티션은 사이드카도 보지 않는다. 목록 항목은 `*` 또는
# 26자 Crockford base32(ingestStreamId)만이다 — 사이드카는 항목을 검증하지 않고
# 꼴이 틀린 항목의 회차를 조용히 버린다(streamer_invalid). 목록을 채우는 것은
# 응답기 모드에서만이다(옛 모드는 4C resolve 가 없어 사이드카가 발행을 끈다).
# 계정 ID · ARN · id 는 모두 가짜 값이다.

mock_provider "aws" {}

override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "123456789012"
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "ap-northeast-2"
  }
}

override_data {
  target = data.aws_partition.current
  values = {
    partition = "aws"
  }
}

# mock 이 지어낸 json 은 정책 JSON 검사를 통과하지 못한다 — statement 입력은 그대로 보인다.
override_data {
  target = data.aws_iam_policy_document.media
  values = {
    json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
  }
}

# 인스턴스 유형의 아키텍처 — 실제로는 AWS 가 답한다.
override_data {
  target = data.aws_ec2_instance_type.this
  values = {
    supported_architectures = ["arm64"]
  }
}

override_data {
  target = data.aws_ssm_parameter.ami
  values = {
    insecure_value = "ami-0123456789abcdef0"
  }
}

variables {
  name             = "pokeclip-prod"
  vpc_id           = "vpc-0123456789abcdef0"
  subnet_id        = "subnet-0media0private000"
  instance_type    = "c8gn.large"
  root_volume_size = 150
  instances = {
    a = { config_version = "1" }
  }
  ingest_active_key = "a"
  http_active_key   = "a"
  target_group_arns = {
    ingest   = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/pokeclip-prod-ingest/0000000000000001"
    live     = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/pokeclip-prod-live/0000000000000002"
    control  = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/pokeclip-prod-control/0000000000000003"
    playback = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/pokeclip-prod-playback/0000000000000004"
  }
  internal_alb_sg_id = "sg-0internalalb000000"
  nlb_sg_id          = "sg-0nlb0000000000000"
  rds_sg_id          = "sg-0rds0000000000000"
  auth_sg_id         = "sg-0auth000000000000"
  auth_port          = 18086
  s3_prefix_list_id  = "pl-0123456789abcdef0"
  proxy_ports = {
    live     = 18081
    playback = 18082
    control  = 18083
    health   = 18084
  }
  media_health_path             = "/media-health"
  bundle_image                  = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-host-bundle@sha256:0000000000000000000000000000000000000000000000000000000000000000"
  mediamtx_image                = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-mediamtx@sha256:1111111111111111111111111111111111111111111111111111111111111111"
  nginx_image                   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-nginx@sha256:2222222222222222222222222222222222222222222222222222222222222222"
  sidecar_image                 = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-sidecar@sha256:3333333333333333333333333333333333333333333333333333333333333333"
  loopback_sender_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-loopback-sender@sha256:4444444444444444444444444444444444444444444444444444444444444444"
  rds_endpoint                  = "pokeclip-prod-db.example.ap-northeast-2.rds.amazonaws.com"
  rds_db_name                   = "pokeclip"
  rds_resource_id               = "db-EXAMPLE0123456789ABCDEFGH"
  rewind_bucket_arn             = "arn:aws:s3:::pokeclip-prod-rewind-example"
  cdn_secret_arn                = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/cdn-secret-AbCdEf"
  test_stream_key_secret_arn    = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/test-stream-key-GhIjKl"
  media_secret_arns             = []
  docker_version                = "25.0.16"
  ecr_credential_helper_version = "0.12.0-3.amzn2023"
  compose_version               = "5.5.0"
  compose_sha256                = "ff42489f5a9b879d5d117c5ffea6defc27390b3286da8ad52cbc9c6ab5df590e"
  broadcast_topic_arn           = "arn:aws:sns:ap-northeast-2:123456789012:pokeclipbroadcast.fifo"
}

# --- T-A 주제 ARN — 받는 값 셋 ---

run "accepts_sns_fifo_topic_arn" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns:ap-northeast-2:123456789012:pokeclipbroadcast.fifo"
  }

  assert {
    condition     = output.template_vars.broadcast_topic_arn == "arn:aws:sns:ap-northeast-2:123456789012:pokeclipbroadcast.fifo"
    error_message = "받아야 할 주제 ARN 이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}

# 사이드카는 파티션을 보지 않는다.
run "accepts_topic_arn_in_other_partition" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws-cn:sns:cn-north-1:123456789012:pokeclipbroadcast.fifo"
  }

  assert {
    condition     = output.template_vars.broadcast_topic_arn == "arn:aws-cn:sns:cn-north-1:123456789012:pokeclipbroadcast.fifo"
    error_message = "받아야 할 주제 ARN 이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}

run "accepts_hyphenated_topic_name" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns:ap-northeast-2:123456789012:pokeclip-prod-broadcast-lifecycle.fifo"
  }

  assert {
    condition     = output.template_vars.broadcast_topic_arn == "arn:aws:sns:ap-northeast-2:123456789012:pokeclip-prod-broadcast-lifecycle.fifo"
    error_message = "받아야 할 주제 ARN 이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}

# --- T-A 주제 ARN — 거부하는 값 아홉(접두 · 콜론 칸 부족 포함) ---

# arn.Parse 는 첫 칸이 「arn」 이 아니면 거부한다. 나머지 칸은 모두 맞는 값이라
# 첫 칸 검사만 이 값을 거부한다.
run "rejects_topic_arn_with_wrong_prefix" {
  command = plan

  variables {
    broadcast_topic_arn = "xrn:aws:sns:ap-northeast-2:123456789012:pokeclipbroadcast.fifo"
  }

  expect_failures = [var.broadcast_topic_arn]
}

run "rejects_topic_arn_of_other_service" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sqs:ap-northeast-2:123456789012:pokeclipbroadcast.fifo"
  }

  expect_failures = [var.broadcast_topic_arn]
}

run "rejects_topic_arn_without_region" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns::123456789012:pokeclipbroadcast.fifo"
  }

  expect_failures = [var.broadcast_topic_arn]
}

run "rejects_topic_arn_with_11_digit_account" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns:ap-northeast-2:12345678901:pokeclipbroadcast.fifo"
  }

  expect_failures = [var.broadcast_topic_arn]
}

run "rejects_topic_arn_with_13_digit_account" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns:ap-northeast-2:1234567890123:pokeclipbroadcast.fifo"
  }

  expect_failures = [var.broadcast_topic_arn]
}

run "rejects_topic_arn_with_letter_in_account" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns:ap-northeast-2:12345678901a:pokeclipbroadcast.fifo"
  }

  expect_failures = [var.broadcast_topic_arn]
}

# 이름 없이 접미만 있으면 사이드카가 거부한다(자원 길이 > 「.fifo」).
run "rejects_topic_arn_with_bare_fifo_suffix" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns:ap-northeast-2:123456789012:.fifo"
  }

  expect_failures = [var.broadcast_topic_arn]
}

# 계약9 는 FIFO 주제다(MessageGroupId=streamId).
run "rejects_standard_topic_arn" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns:ap-northeast-2:123456789012:pokeclipbroadcast"
  }

  expect_failures = [var.broadcast_topic_arn]
}

# arn.Parse 는 콜론으로 가른 칸이 여섯이어야 한다.
run "rejects_topic_arn_missing_section" {
  command = plan

  variables {
    broadcast_topic_arn = "arn:aws:sns:ap-northeast-2:123456789012"
  }

  expect_failures = [var.broadcast_topic_arn]
}

# --- T-C 목록 항목 — 받는 값(응답기 모드) ---

run "accepts_wildcard_publish_stream" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["*"]
  }

  assert {
    condition     = output.template_vars.lifecycle_publish_streams == tolist(["*"])
    error_message = "받아야 할 발행 목록이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}

run "accepts_crockford_publish_stream" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAV"]
  }

  assert {
    condition     = output.template_vars.lifecycle_publish_streams == tolist(["01ARZ3NDEKTSV4RRFFQ69G5FAV"])
    error_message = "받아야 할 발행 목록이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}

run "accepts_two_publish_streams" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAV", "01BX5ZZKBKACTAV9WEVGEMMVRZ"]
  }

  assert {
    condition     = output.template_vars.lifecycle_publish_streams == tolist(["01ARZ3NDEKTSV4RRFFQ69G5FAV", "01BX5ZZKBKACTAV9WEVGEMMVRZ"])
    error_message = "받아야 할 발행 목록이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}

# --- T-C 목록 항목 — 거부하는 값(응답기 모드라 모드 조합 검증은 통과한다) ---

run "rejects_lowercase_publish_stream" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01arz3ndektsv4rrffq69g5fav"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

# Crockford base32 는 I · L · O · U 를 쓰지 않는다.
run "rejects_publish_stream_with_i" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAI"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

run "rejects_publish_stream_with_l" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAL"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

run "rejects_publish_stream_with_o" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAO"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

run "rejects_publish_stream_with_u" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAU"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

run "rejects_25_char_publish_stream" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FA"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

run "rejects_27_char_publish_stream" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAVV"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

# 사이드카는 앞뒤 공백을 걷지만 IAM · 운영 대조와 어긋나지 않게 받지 않는다.
run "rejects_padded_publish_stream" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = [" 01ARZ3NDEKTSV4RRFFQ69G5FAV "]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

# 쉼표는 렌더에서 항목 경계가 된다 — 한 항목 안에 두지 않는다.
run "rejects_comma_in_publish_stream" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAV,01BX5ZZKBKACTAV9WEVGEMMVRZ"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

run "rejects_empty_publish_stream" {
  command = plan

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = [""]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

# --- T-M 모드 조합 ---

# 옛 모드는 발행 env 두 줄을 렌더하지 않아 사이드카가 발행을 끈다(no_topic) — 목록을
# 채운 채 apply 해야 알지 않게 plan 에서 막는다.
run "rejects_publish_streams_in_legacy_mode" {
  command = plan

  variables {
    ingest_auth_image         = ""
    stream_key_resolve_url    = ""
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAV"]
  }

  expect_failures = [var.lifecycle_publish_streams]
}

run "accepts_legacy_mode_with_empty_publish_streams" {
  command = plan

  variables {
    ingest_auth_image         = ""
    stream_key_resolve_url    = ""
    lifecycle_publish_streams = []
  }

  assert {
    condition     = length(output.template_vars.lifecycle_publish_streams) == 0
    error_message = "옛 모드 · 빈 목록이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}
