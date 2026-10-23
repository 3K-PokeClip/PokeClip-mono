# Media 상자 모듈의 입력 검증을 단언한다 — 프록시 포트(응답기 · MediaMTX
# 루프백 포트와 겹침 금지) · 이미지 참조 꼴과 ECR 호스트 접두 · 활성 키 ·
# 비밀 ARN · 버킷 ARN · DB 리소스 id.

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
}

run "accepts_valid_inputs" {
  command = plan
}

# 응답기가 같은 망 칸의 127.0.0.1:8080 · 8081 을 쓴다(M15 통지).
run "rejects_responder_ports" {
  command = plan

  variables {
    proxy_ports = {
      live     = 8080
      playback = 18082
      control  = 18083
      health   = 18084
    }
  }

  expect_failures = [var.proxy_ports]
}

# MediaMTX 가 망 칸 루프백에 8888 · 9996 · 9997 을 묶는다.
run "rejects_mediamtx_loopback_ports" {
  command = plan

  variables {
    proxy_ports = {
      live     = 18081
      playback = 9996
      control  = 18083
      health   = 18084
    }
  }

  expect_failures = [var.proxy_ports]
}

run "rejects_privileged_ports" {
  command = plan

  variables {
    proxy_ports = {
      live     = 18081
      playback = 18082
      control  = 18083
      health   = 443
    }
  }

  expect_failures = [var.proxy_ports]
}

run "rejects_duplicate_ports" {
  command = plan

  variables {
    proxy_ports = {
      live     = 18081
      playback = 18081
      control  = 18083
      health   = 18084
    }
  }

  expect_failures = [var.proxy_ports]
}

run "rejects_image_without_digest" {
  command = plan

  variables {
    sidecar_image = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-sidecar:latest"
  }

  expect_failures = [var.sidecar_image]
}

run "rejects_bundle_image_without_digest" {
  command = plan

  variables {
    bundle_image = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-host-bundle@sha256:abc"
  }

  expect_failures = [var.bundle_image]
}

# 이미지 참조는 이 계정 ECR 레지스트리 호스트로 시작해야 한다(media.env 의
# ECR_REGISTRY_HOST 와 같은 값).
run "rejects_image_outside_account_registry" {
  command = plan

  variables {
    nginx_image = "docker.io/library/nginx@sha256:2222222222222222222222222222222222222222222222222222222222222222"
  }

  expect_failures = [aws_instance.this]
}

run "accepts_empty_or_digest_ingest_auth_image" {
  command = plan

  variables {
    ingest_auth_image = ""
  }
}

run "rejects_ingest_auth_image_tag" {
  command = plan

  variables {
    ingest_auth_image = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth:v1"
  }

  expect_failures = [var.ingest_auth_image]
}

run "rejects_unknown_active_key" {
  command = plan

  variables {
    http_active_key = "b"
  }

  expect_failures = [var.http_active_key]
}

run "rejects_wildcard_secret_arn" {
  command = plan

  variables {
    media_secret_arns = ["arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/*"]
  }

  expect_failures = [var.media_secret_arns]
}

run "rejects_non_bucket_rewind_arn" {
  command = plan

  variables {
    rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example/*"
  }

  expect_failures = [var.rewind_bucket_arn]
}

run "rejects_wildcard_rds_resource_id" {
  command = plan

  variables {
    rds_resource_id = "*"
  }

  expect_failures = [var.rds_resource_id]
}

run "rejects_malformed_compose_checksum" {
  command = plan

  variables {
    compose_sha256 = "not-a-checksum"
  }

  expect_failures = [var.compose_sha256]
}

# 상자는 arm64 다(이미지 · compose · 자격 도우미가 모두 arm64 판).
run "rejects_non_arm64_instance_type" {
  command = plan

  override_data {
    target = data.aws_ec2_instance_type.this
    values = {
      supported_architectures = ["x86_64"]
    }
  }

  expect_failures = [aws_instance.this]
}

run "rejects_low_root_volume_throughput" {
  command = plan

  variables {
    root_volume_throughput = 100
  }

  expect_failures = [var.root_volume_throughput]
}

# 계열 이름에 Graviton 표지 g 가 있어야 한다(AWS 답을 읽기 전, 입력 단계에서).
run "accepts_graviton_instance_types" {
  command = plan

  variables {
    instance_type = "c7g.large"
  }
}

run "accepts_other_graviton_families" {
  command = plan

  variables {
    instance_type = "t4g.small"
  }
}

run "rejects_intel_instance_type_name" {
  command = plan

  variables {
    instance_type = "c7i.large"
  }

  expect_failures = [var.instance_type]
}

run "rejects_burstable_x86_instance_type_name" {
  command = plan

  variables {
    instance_type = "t3.micro"
  }

  expect_failures = [var.instance_type]
}

# IOPS 를 기본 3,000 으로 두므로 처리량 상한은 3,000 × 0.25 = 750 MiB/s 다.
run "rejects_root_volume_throughput_above_iops_ratio" {
  command = plan

  variables {
    root_volume_throughput = 800
  }

  expect_failures = [var.root_volume_throughput]
}

# 응답기 4C 주소는 compose 에 따옴표 없는 스칼라로 들어간다 — http://<호스트>:<포트>
# 꼴만 받는다. 옛 모드(응답기 이미지 빈 값)는 빈 값이어도 된다. 응답기 모드의
# 빈 값은 아직 막지 않는다 — 루트가 값을 넘기기 전(M9 후속 배선)이라서다.
run "accepts_responder_mode_with_stream_key_resolve_url" {
  command = plan

  variables {
    ingest_auth_image      = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url = "http://auth.pokeclip.internal:8085"
  }

  assert {
    condition     = output.template_vars.stream_key_resolve_url == "http://auth.pokeclip.internal:8085"
    error_message = "stream_key_resolve_url 이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}

run "rejects_stream_key_resolve_url_with_userinfo" {
  command = plan

  variables {
    stream_key_resolve_url = "http://user@auth.pokeclip.internal:8085"
  }

  expect_failures = [var.stream_key_resolve_url]
}

run "rejects_stream_key_resolve_url_with_query" {
  command = plan

  variables {
    stream_key_resolve_url = "http://auth.pokeclip.internal:8085?channel=x"
  }

  expect_failures = [var.stream_key_resolve_url]
}

run "rejects_stream_key_resolve_url_with_fragment" {
  command = plan

  variables {
    stream_key_resolve_url = "http://auth.pokeclip.internal:8085#x"
  }

  expect_failures = [var.stream_key_resolve_url]
}

run "rejects_stream_key_resolve_url_with_space" {
  command = plan

  variables {
    stream_key_resolve_url = "http://auth pokeclip:8085"
  }

  expect_failures = [var.stream_key_resolve_url]
}

run "rejects_stream_key_resolve_url_with_dollar" {
  command = plan

  variables {
    stream_key_resolve_url = "http://auth$x.pokeclip.internal:8085"
  }

  expect_failures = [var.stream_key_resolve_url]
}

run "rejects_stream_key_resolve_url_without_port" {
  command = plan

  variables {
    stream_key_resolve_url = "http://auth.pokeclip.internal"
  }

  expect_failures = [var.stream_key_resolve_url]
}

run "accepts_ingest_auth_ip_limit_off" {
  command = plan

  variables {
    ingest_auth_ip_limit = "off"
  }

  assert {
    condition     = output.template_vars.ingest_auth_ip_limit == "off"
    error_message = "ingest_auth_ip_limit 이 템플릿 변수로 그대로 넘어가지 않았다."
  }
}

run "rejects_unknown_ingest_auth_ip_limit" {
  command = plan

  variables {
    ingest_auth_ip_limit = "true"
  }

  expect_failures = [var.ingest_auth_ip_limit]
}
