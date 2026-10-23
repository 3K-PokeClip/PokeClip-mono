# Media 상자 user_data(계획 M9 c1 「렌더된 user_data 단언」 · kty 결정 —
# ECR 설치 묶음 이미지)를 단언한다.
#
# user_data 는 `#cloud-config` 이고 gzip + base64 다. Terraform 에는 gzip 을 푸는
# 함수가 없어 모듈의 시험용 출력 rendered_user_data(비밀 없음)를 yamldecode 로
# 읽는다. 스크립트 · 상자에서 렌더하는 MediaMTX 템플릿은 설치 묶음 이미지에
# 있고, user_data 에는 Terraform 이 값을 넣는 파일 · 유닛 · 부트스트랩만 있다.
# 묶음 내용이 저장소와 같은지는 묶음 Dockerfile 이 저장소 경로를 COPY 하는
# 것으로 갈음한다(README 「설치 묶음」).

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
    a = { config_version = "7" }
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

# EC2 user_data 한도 16 KiB 는 base64 를 풀기 전(압축본) 크기에 걸린다.
run "user_data_is_gzip_base64_under_16_kib" {
  command = plan

  assert {
    condition     = aws_instance.this["a"].user_data_base64 == base64gzip(output.rendered_user_data["a"])
    error_message = "user_data_base64 가 rendered_user_data 의 gzip + base64 가 아니다."
  }

  assert {
    condition     = floor(length(aws_instance.this["a"].user_data_base64) / 4) * 3 < 16384
    error_message = "압축 user_data 가 16 KiB 이상이다 — 재회부."
  }

  assert {
    condition     = startswith(output.rendered_user_data["a"], "#cloud-config\n")
    error_message = "user_data 가 #cloud-config 로 시작하지 않는다."
  }
}

# 가장 큰 렌더(응답기 · janitor · 제어면 상류 모두 켬)도 한도 안이다.
run "largest_user_data_under_16_kib" {
  command = plan

  variables {
    ingest_auth_image = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    janitor_enabled   = true
    control_upstream  = "127.0.0.1:19090"
  }

  assert {
    condition     = floor(length(aws_instance.this["a"].user_data_base64) / 4) * 3 < 16384
    error_message = "가장 큰 압축 user_data 가 16 KiB 이상이다 — 재회부."
  }
}

# README 「파일 · 서비스 지도」 · 「후속」 M9 행: 자리 · 소유 · 모드.
run "writes_files_with_owner_and_mode" {
  command = plan

  assert {
    condition = {
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.path => [file.owner, file.permissions]
      } == {
      "/etc/pokeclip-media/media.env"                               = ["root:root", "0644"]
      "/etc/pokeclip-media/compose.yml"                             = ["root:root", "0644"]
      "/etc/pokeclip-media/nginx.conf"                              = ["root:root", "0644"]
      "/root/.docker/config.json"                                   = ["root:root", "0600"]
      "/etc/systemd/system/pokeclip-media.service"                  = ["root:root", "0644"]
      "/etc/systemd/system/pokeclip-media.service.d/10-bundle.conf" = ["root:root", "0644"]
      "/etc/systemd/journald.conf.d/10-pokeclip-media.conf"         = ["root:root", "0644"]
      "/usr/local/sbin/pokeclip-media-bootstrap"                    = ["root:root", "0700"]
    }
    error_message = "write_files 의 자리 · 소유 · 모드가 다르다."
  }

  # 유닛은 저장소 파일 그대로다(묶음을 꺼내지 못해도 유닛이 있어야 기동 차단이
  # 유닛 저널에 남는다).
  assert {
    condition = [
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.content if file.path == "/etc/systemd/system/pokeclip-media.service"
    ] == [file("../../../media-host/systemd/pokeclip-media.service")]
    error_message = "유닛 파일이 저장소 systemd/pokeclip-media.service 와 다르다."
  }

  assert {
    condition     = yamldecode(output.rendered_user_data["a"]).runcmd == [["/usr/local/sbin/pokeclip-media-bootstrap"]]
    error_message = "runcmd 가 부트스트랩 하나가 아니다."
  }
}

run "renders_compose_and_nginx_from_repository_templates" {
  command = plan

  assert {
    condition = [
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.content if file.path == "/etc/pokeclip-media/compose.yml"
    ] == [templatefile("../../../media-host/compose.yml.tmpl", output.template_vars)]
    error_message = "compose.yml 이 저장소 템플릿의 렌더가 아니다."
  }

  assert {
    condition = [
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.content if file.path == "/etc/pokeclip-media/nginx.conf"
    ] == [templatefile("../../../media-host/nginx/nginx.conf.tmpl", output.template_vars)]
    error_message = "nginx.conf 가 저장소 템플릿의 렌더가 아니다."
  }

  # 템플릿 변수 키 집합은 시험값 파일(render_vars.json)과 같다.
  assert {
    condition     = toset(keys(output.template_vars)) == toset(keys(jsondecode(file("../../../media-host/test/render_vars.json"))))
    error_message = "템플릿 변수 키 집합이 infra/media-host/test/render_vars.json 과 다르다."
  }

  # README 「후속」: 문자 그대로 "journald" · janitor_enabled 는 bool(기본 true).
  assert {
    condition     = output.template_vars.detached_log_driver == "journald" && output.template_vars.janitor_enabled == true
    error_message = "detached_log_driver 가 \"journald\" 가 아니거나 janitor_enabled 가 bool true 가 아니다."
  }

  assert {
    condition = (
      output.template_vars.rewind_bucket_name == "pokeclip-prod-rewind-example" &&
      output.template_vars.rds_endpoint == "pokeclip-prod-db.example.ap-northeast-2.rds.amazonaws.com" &&
      output.template_vars.rds_db_name == "pokeclip" &&
      output.template_vars.proxy_live_port == 18081 &&
      output.template_vars.proxy_playback_port == 18082 &&
      output.template_vars.proxy_control_port == 18083 &&
      output.template_vars.proxy_health_port == 18084 &&
      output.template_vars.media_health_path == "/media-health" &&
      output.template_vars.ingest_auth_image == ""
    )
    error_message = "템플릿 변수 값(버킷 이름 · DB · 포트 · 헬스 경로 · 응답기)이 입력과 다르다."
  }

  assert {
    condition = (
      strcontains(output.rendered_user_data["a"], "POSTGRES_AUTH: iam") &&
      strcontains(output.rendered_user_data["a"], "S3_BUCKET: pokeclip-prod-rewind-example") &&
      !strcontains(output.rendered_user_data["a"], "POSTGRES_PASSWORD")
    )
    error_message = "사이드카 env 가 IAM 인증 · 버킷 이름이 아니거나 POSTGRES_PASSWORD 가 있다."
  }

  # 응답기는 M15 몫 — 빈 값이면 렌더하지 않는다. janitor 는 기본 켬.
  assert {
    condition = (
      !strcontains(output.rendered_user_data["a"], "ingest-auth:") &&
      strcontains(output.rendered_user_data["a"], "  janitor:")
    )
    error_message = "빈 응답기 이미지인데 서비스가 있거나 janitor 가 없다."
  }

  # 제어면 서버가 없으면 nginx 가 제어면 경로를 404 로 답한다.
  assert {
    condition = (
      strcontains(output.rendered_user_data["a"], "return 404") &&
      !strcontains(output.rendered_user_data["a"], "proxy_pass http://127.0.0.1:19090")
    )
    error_message = "control_upstream 이 빈 값인데 제어면 404 가 없다."
  }
}

# 사이드카는 S3_BUCKET 이 있으면 REWIND_PUBLIC_BASE_URL 이 없을 때 기동을 거부한다
# (media/internal/config/config.go). 목록 줄 = 앞머리 + "/" + S3 키(dvr/…) — 기본은
# 영상 CDN 주소다(설계 4-5 /dvr/* → rewind-s3).
run "renders_rewind_public_base_url_for_sidecar" {
  command = plan

  assert {
    condition = (
      output.template_vars.rewind_public_base_url == "https://media.pokeclip.com" &&
      strcontains(one([
        for file in yamldecode(output.rendered_user_data["a"]).write_files :
        file.content if file.path == "/etc/pokeclip-media/compose.yml"
      ]), "REWIND_PUBLIC_BASE_URL: \"https://media.pokeclip.com\"")
    )
    error_message = "사이드카 env 에 REWIND_PUBLIC_BASE_URL(기본 https://media.pokeclip.com)이 없다."
  }
}

run "passes_rewind_public_base_url_input" {
  command = plan

  variables {
    rewind_public_base_url = "https://cdn.example.com/rewind"
  }

  assert {
    condition = strcontains(one([
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.content if file.path == "/etc/pokeclip-media/compose.yml"
    ]), "REWIND_PUBLIC_BASE_URL: \"https://cdn.example.com/rewind\"")
    error_message = "rewind_public_base_url 입력이 사이드카 env 에 그대로 가지 않는다."
  }
}

# 사이드카 검증(https · 끝 / 없음 · query · fragment · dot 세그먼트 거부)보다 먼저
# plan 에서 막는다 — 상자에서 막히면 사이드카가 재시작을 되풀이한다.
run "rejects_http_rewind_public_base_url" {
  command = plan

  variables {
    rewind_public_base_url = "http://media.pokeclip.com"
  }

  expect_failures = [var.rewind_public_base_url]
}

run "rejects_trailing_slash_rewind_public_base_url" {
  command = plan

  variables {
    rewind_public_base_url = "https://media.pokeclip.com/"
  }

  expect_failures = [var.rewind_public_base_url]
}

run "rejects_query_rewind_public_base_url" {
  command = plan

  variables {
    rewind_public_base_url = "https://media.pokeclip.com?x=1"
  }

  expect_failures = [var.rewind_public_base_url]
}

run "rejects_dot_segment_rewind_public_base_url" {
  command = plan

  variables {
    rewind_public_base_url = "https://media.pokeclip.com/a/../b"
  }

  expect_failures = [var.rewind_public_base_url]
}

run "toggles_optional_services_and_control_upstream" {
  command = plan

  variables {
    ingest_auth_image = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    janitor_enabled   = false
    control_upstream  = "127.0.0.1:19090"
  }

  assert {
    condition = (
      strcontains(output.rendered_user_data["a"], "ingest-auth:") &&
      !strcontains(output.rendered_user_data["a"], "  janitor:") &&
      strcontains(output.rendered_user_data["a"], "127.0.0.1:19090")
    )
    error_message = "응답기 이미지 · janitor 끔 · control_upstream 이 렌더에 반영되지 않았다."
  }
}

run "writes_media_env_and_docker_config" {
  command = plan

  assert {
    condition = toset(split("\n", trimspace(one([
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.content if file.path == "/etc/pokeclip-media/media.env"
      ])))) == toset([
      "MEDIA_EXPECTED_COMPOSE_VERSION=5.5.0",
      "ECR_REGISTRY_HOST=123456789012.dkr.ecr.ap-northeast-2.amazonaws.com",
      "CDN_SECRET_ARN=arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/cdn-secret-AbCdEf",
      "TEST_STREAM_KEY_SECRET_ARN=arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/test-stream-key-GhIjKl",
      "LOOPBACK_SENDER_IMAGE=123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-loopback-sender@sha256:4444444444444444444444444444444444444444444444444444444444444444",
      "MEDIA_LIVE_PORT=18081",
      "INGEST_AUTH_DENYLIST_PARAM=/pokeclip/prod/media/ingest-auth-deny-tokens",
      "MEDIA_CONFIG_VERSION=7",
      "MEDIA_BUNDLE_IMAGE=123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-host-bundle@sha256:0000000000000000000000000000000000000000000000000000000000000000",
    ])
    error_message = "media.env 의 줄(기대 compose 판 · ECR 호스트 · 비밀 ARN · 송출기 · live 포트 · 거부 목록 · 구성 판 · 묶음)이 다르다."
  }

  assert {
    condition = jsondecode(one([
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.content if file.path == "/root/.docker/config.json"
    ])) == { credHelpers = { "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com" = "ecr-login" } }
    error_message = "docker config.json 이 ECR 호스트 → ecr-login credHelpers 하나가 아니다."
  }

  # 설치 실패도 기동 차단 — 묶음 설치 표지가 없으면 시작 스크립트와 같은 꼴로 막는다.
  assert {
    condition = strcontains(one([
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.content if file.path == "/etc/systemd/system/pokeclip-media.service.d/10-bundle.conf"
    ]), "MEDIA PREREQ FAIL bundle")
    error_message = "유닛 drop-in 에 묶음 설치 실패 차단(MEDIA PREREQ FAIL bundle)이 없다."
  }

  assert {
    condition = strcontains(one([
      for file in yamldecode(output.rendered_user_data["a"]).write_files :
      file.content if file.path == "/etc/systemd/journald.conf.d/10-pokeclip-media.conf"
    ]), "Storage=persistent")
    error_message = "journald 설정이 영속 저장(Storage=persistent)이 아니다."
  }
}

# 판 · 체크섬 고정 설치와 묶음 꺼내기.
run "bootstrap_pins_tools_and_extracts_bundle" {
  command = plan

  assert {
    condition = alltrue([
      for needle in [
        "docker-25.0.16",
        "amazon-ecr-credential-helper-0.12.0-3.amzn2023",
        "https://github.com/docker/compose/releases/download/v5.5.0/docker-compose-linux-aarch64",
        "ff42489f5a9b879d5d117c5ffea6defc27390b3286da8ad52cbc9c6ab5df590e",
        "sha256sum -c",
        "/usr/libexec/docker/cli-plugins/docker-compose",
        "systemctl enable --now docker",
        "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-host-bundle@sha256:0000000000000000000000000000000000000000000000000000000000000000",
        "docker create",
        "docker cp",
        "install_tree bin /opt/pokeclip-media/bin 0755 0755",
        "install_tree bin/lib /opt/pokeclip-media/bin/lib 0755 0644",
        "/etc/pokeclip-media/mediamtx.yml.tmpl",
        "systemctl enable pokeclip-media",
      ] :
      strcontains(one([
        for file in yamldecode(output.rendered_user_data["a"]).write_files :
        file.content if file.path == "/usr/local/sbin/pokeclip-media-bootstrap"
      ]), needle)
    ])
    error_message = "부트스트랩에 판 · 체크섬 고정 설치 · 묶음 꺼내기 · 유닛 enable 중 빠진 것이 있다."
  }
}

# 시험 이음매 값 · 비밀 계열 이름이 운영 렌더에 새지 않는다.
run "leaks_no_test_seams" {
  command = plan

  assert {
    condition = alltrue([
      for needle in ["edgebench", "fakebin", "pcbench", "SecretString", "PASSWORD"] :
      !strcontains(output.rendered_user_data["a"], needle)
    ])
    error_message = "렌더에 시험 이음매 값 · 비밀 계열 이름이 있다."
  }
}
