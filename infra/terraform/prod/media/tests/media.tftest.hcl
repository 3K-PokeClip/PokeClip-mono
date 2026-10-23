# prod/media 루트(설계 3-1 의 ⑨ · 3-2 · 계획 M9 c2)를 mock provider 로 단언한다
# — 앞 루트 출력(network · platform · secrets · registry · 런타임 1단)을 모듈에
# 넘기는 선, 이미지 참조 조립(registry repo_urls + 다이제스트), 기본값(janitor
# 켬 · 제어면 상류 빈 값), 출력 넷.
#
# 앞 루트의 상태는 override_data 로 흉내 낸다. 계정 ID 는 가짜 12자리
# (111122223333)다. 루트 시험은 하위 모듈 안의 자원(SG 규칙 · 인스턴스 ·
# 정책)을 주소로 볼 수 없어 모듈 출력(template_vars · rendered_user_data)과
# 루트 출력만 본다. 모듈 안 동작은 modules/media_host 시험이 맡는다.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
    }
  }

  mock_data "aws_region" {
    defaults = {
      region = "ap-northeast-2"
    }
  }

  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }

  mock_data "aws_ssm_parameter" {
    defaults = {
      insecure_value = "ami-0123456789abcdef0"
    }
  }
}

# 인스턴스 유형의 아키텍처 — 실제로는 AWS 가 답한다.
override_data {
  target = module.media_host.data.aws_ec2_instance_type.this
  values = {
    supported_architectures = ["arm64"]
  }
}

# mock 이 지어낸 json 은 정책 JSON 검사를 통과하지 못한다.
override_data {
  target = module.media_host.data.aws_iam_policy_document.media
  values = {
    json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
  }
}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id                  = "vpc-0example"
      vpc_cidr                = "192.0.2.0/24"
      public_subnet_ids       = ["subnet-0puba", "subnet-0pubc"]
      private_subnet_ids      = ["subnet-0priva", "subnet-0privc"]
      media_az                = "ap-northeast-2c"
      media_public_subnet_id  = "subnet-0pubc"
      media_private_subnet_id = "subnet-0privc"
      s3_prefix_list_id       = "pl-0example"
    }
  }
}

override_data {
  target = data.terraform_remote_state.platform
  values = {
    outputs = {
      internal_alb_sg_id = "sg-0internalalb"
      nlb_sg_id          = "sg-0nlb"
      media_proxy_ports = {
        live     = 28181
        playback = 28182
        control  = 28183
        health   = 28184
      }
      media_health_path = "/example-proxy-health"
      target_group_arns = {
        ingest   = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-ingest/0000000000000001"
        live     = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-live/0000000000000002"
        control  = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-control/0000000000000003"
        playback = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/pokeclip-prod-internal-playback/0000000000000004"
      }
    }
  }
}

override_data {
  target = data.terraform_remote_state.secrets
  values = {
    outputs = {
      public_key_id              = "K0EXAMPLEKEYID"
      signing_key_secret_arn     = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/signing-key-AbCdEf"
      cdn_secret_arn             = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/cdn-secret-GhIjKl"
      test_stream_key_secret_arn = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/test-stream-key-MnOpQr"
    }
  }
}

override_data {
  target = data.terraform_remote_state.registry
  values = {
    outputs = {
      repo_urls = {
        "media-host-bundle"     = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-host-bundle"
        "media-mediamtx"        = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-mediamtx"
        "media-nginx"           = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-nginx"
        "media-sidecar"         = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-sidecar"
        "media-loopback-sender" = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-loopback-sender"
        "media-ingest-auth"     = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth"
      }
    }
  }
}

override_data {
  target = data.terraform_remote_state.runtime
  values = {
    outputs = {
      rds_endpoint      = "pokeclip-prod-db.example.ap-northeast-2.rds.amazonaws.com"
      rds_db_name       = "pokeclip"
      rds_resource_id   = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
      rds_sg_id         = "sg-0rds"
      rewind_bucket_arn = "arn:aws:s3:::pokeclip-prod-rewind-example"
      media_secret_arns = ["arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"]
    }
  }
}

variables {
  instances = {
    a = { config_version = "1" }
  }
  ingest_active_key = "a"
  http_active_key   = "a"
  instance_type     = "c8gn.large"
  root_volume_size  = 150
  image_digests = {
    bundle          = "sha256:0000000000000000000000000000000000000000000000000000000000000000"
    mediamtx        = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    nginx           = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
    sidecar         = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
    loopback_sender = "sha256:4444444444444444444444444444444444444444444444444444444444444444"
  }
  docker_version                = "25.0.16"
  ecr_credential_helper_version = "0.12.0-3.amzn2023"
  compose_version               = "5.5.0"
  compose_sha256                = "ff42489f5a9b879d5d117c5ffea6defc27390b3286da8ad52cbc9c6ab5df590e"
}

# --- 이미지 참조(registry repo_urls + 다이제스트) ---

run "composes_image_refs_from_registry_and_digests" {
  command = plan

  assert {
    condition = (
      module.media_host.template_vars.mediamtx_image == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-mediamtx@sha256:1111111111111111111111111111111111111111111111111111111111111111" &&
      module.media_host.template_vars.nginx_image == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-nginx@sha256:2222222222222222222222222222222222222222222222222222222222222222" &&
      module.media_host.template_vars.sidecar_image == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-sidecar@sha256:3333333333333333333333333333333333333333333333333333333333333333"
    )
    error_message = "MediaMTX · nginx · 사이드카 이미지가 <registry repo_urls[키]>@<다이제스트> 가 아니다(저장소 · 다이제스트 짝이 바뀜)."
  }

  assert {
    condition = (
      strcontains(module.media_host.rendered_user_data["a"], "MEDIA_BUNDLE_IMAGE=111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-host-bundle@sha256:0000000000000000000000000000000000000000000000000000000000000000\n") &&
      strcontains(module.media_host.rendered_user_data["a"], "LOOPBACK_SENDER_IMAGE=111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-loopback-sender@sha256:4444444444444444444444444444444444444444444444444444444444444444\n")
    )
    error_message = "media.env 의 설치 묶음 · 루프백 송출기 이미지가 <registry repo_urls[키]>@<다이제스트> 가 아니다."
  }

  # 응답기는 M15 몫 — 다이제스트를 주지 않으면 빈 값이라 compose 에서 빠진다.
  assert {
    condition     = module.media_host.template_vars.ingest_auth_image == ""
    error_message = "응답기 다이제스트를 주지 않았는데 ingest_auth_image 가 빈 값이 아니다(`…@` 로 조립됨)."
  }
}

run "composes_ingest_auth_image_when_digest_given" {
  command = plan

  variables {
    image_digests = {
      bundle          = "sha256:0000000000000000000000000000000000000000000000000000000000000000"
      mediamtx        = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      nginx           = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
      sidecar         = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
      loopback_sender = "sha256:4444444444444444444444444444444444444444444444444444444444444444"
      ingest_auth     = "sha256:5555555555555555555555555555555555555555555555555555555555555555"
    }
  }

  assert {
    condition     = module.media_host.template_vars.ingest_auth_image == "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    error_message = "응답기 다이제스트를 줬는데 ingest_auth_image 가 <repo_urls[media-ingest-auth]>@<다이제스트> 가 아니다."
  }
}

# --- 앞 루트 출력 → 모듈 ---

run "passes_runtime_and_secret_outputs" {
  command = plan

  assert {
    condition = (
      module.media_host.template_vars.rds_endpoint == "pokeclip-prod-db.example.ap-northeast-2.rds.amazonaws.com" &&
      module.media_host.template_vars.rds_db_name == "pokeclip" &&
      module.media_host.template_vars.rewind_bucket_name == "pokeclip-prod-rewind-example"
    )
    error_message = "사이드카 env(DB 주소 · DB 이름 · rewind 버킷 이름)가 런타임 1단 출력과 다르다."
  }

  assert {
    condition = (
      strcontains(module.media_host.rendered_user_data["a"], "CDN_SECRET_ARN=arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/cdn-secret-GhIjKl\n") &&
      strcontains(module.media_host.rendered_user_data["a"], "TEST_STREAM_KEY_SECRET_ARN=arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/test-stream-key-MnOpQr\n")
    )
    error_message = "media.env 의 CDN 비밀 · 시험 키 ARN 이 prod/secrets 출력과 다르다."
  }

  assert {
    condition     = strcontains(module.media_host.rendered_user_data["a"], "ECR_REGISTRY_HOST=111122223333.dkr.ecr.ap-northeast-2.amazonaws.com\n")
    error_message = "media.env 의 ECR_REGISTRY_HOST 가 이 계정 레지스트리 호스트가 아니다."
  }
}

# 포트 · 헬스 경로의 정본은 platform 이다(대상 그룹과 같은 값 — CONTRACT-runtime.md).
run "reads_proxy_ports_and_health_path_from_platform" {
  command = plan

  assert {
    condition = (
      module.media_host.template_vars.proxy_live_port == 28181 &&
      module.media_host.template_vars.proxy_playback_port == 28182 &&
      module.media_host.template_vars.proxy_control_port == 28183 &&
      module.media_host.template_vars.proxy_health_port == 28184 &&
      module.media_host.template_vars.media_health_path == "/example-proxy-health"
    )
    error_message = "nginx 포트 넷 · 헬스 경로가 platform 출력 media_proxy_ports · media_health_path 와 다르다."
  }
}

run "defaults_janitor_on_and_control_upstream_empty" {
  command = plan

  assert {
    condition     = module.media_host.template_vars.janitor_enabled == true
    error_message = "janitor_enabled 기본값이 켬(true)이 아니다(kty 확정)."
  }

  assert {
    condition     = module.media_host.template_vars.control_upstream == ""
    error_message = "control_upstream 기본값이 빈 값이 아니다(I1-6 배포 전 — nginx 제어면 404)."
  }
}

run "passes_control_upstream_and_janitor_off" {
  command = plan

  variables {
    control_upstream = "127.0.0.1:18090"
    janitor_enabled  = false
  }

  assert {
    condition = (
      module.media_host.template_vars.control_upstream == "127.0.0.1:18090" &&
      module.media_host.template_vars.janitor_enabled == false
    )
    error_message = "control_upstream · janitor_enabled 입력이 모듈에 그대로 가지 않는다."
  }
}

# 사이드카 되감기 목록 앞머리 — 기본은 영상 CDN 주소(설계 4-5 /dvr/* → rewind-s3).
run "defaults_rewind_public_base_url_to_media_cdn" {
  command = plan

  assert {
    condition     = module.media_host.template_vars.rewind_public_base_url == "https://media.pokeclip.com"
    error_message = "rewind_public_base_url 기본값이 https://media.pokeclip.com 이 아니다."
  }
}

run "passes_rewind_public_base_url_input" {
  command = plan

  variables {
    rewind_public_base_url = "https://cdn.example.com/rewind"
  }

  assert {
    condition     = module.media_host.template_vars.rewind_public_base_url == "https://cdn.example.com/rewind"
    error_message = "rewind_public_base_url 입력이 모듈에 그대로 가지 않는다."
  }
}

# --- 출력(설계 3-2) ---

# id 는 계산 속성이다. apply 로 돌려 mock 이 자원마다 다른 값을 만들게 한다.
run "outputs_active_keys_media_sg_and_instance_ids" {
  command = apply

  variables {
    instances = {
      a = { config_version = "1" }
      b = { config_version = "2" }
    }
    ingest_active_key = "b"
    http_active_key   = "a"
  }

  assert {
    condition     = output.ingest_active_key == "b" && output.http_active_key == "a"
    error_message = "출력 ingest_active_key · http_active_key 가 입력과 다르다."
  }

  assert {
    condition     = output.media_sg_id == module.media_host.media_sg_id
    error_message = "출력 media_sg_id 가 모듈의 Media SG id 와 다르다."
  }

  assert {
    condition     = output.instance_ids == module.media_host.instance_ids && toset(keys(output.instance_ids)) == toset(["a", "b"])
    error_message = "출력 instance_ids 가 모듈 출력(상자 키 a · b → id)과 다르다."
  }
}

# --- 입력 검증 ---

run "rejects_image_tag_instead_of_digest" {
  command = plan

  variables {
    image_digests = {
      bundle          = "latest"
      mediamtx        = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      nginx           = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
      sidecar         = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
      loopback_sender = "sha256:4444444444444444444444444444444444444444444444444444444444444444"
    }
  }

  expect_failures = [var.image_digests]
}

run "rejects_malformed_ingest_auth_digest" {
  command = plan

  variables {
    image_digests = {
      bundle          = "sha256:0000000000000000000000000000000000000000000000000000000000000000"
      mediamtx        = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      nginx           = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
      sidecar         = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
      loopback_sender = "sha256:4444444444444444444444444444444444444444444444444444444444444444"
      ingest_auth     = "sha256:5555"
    }
  }

  expect_failures = [var.image_digests]
}
