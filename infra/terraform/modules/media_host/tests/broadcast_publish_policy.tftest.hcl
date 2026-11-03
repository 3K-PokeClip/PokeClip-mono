# Media 인스턴스 역할의 계약9 발행 권한을 정책 JSON(정책 문서가 실제로 낸
# json)에서 단언한다(O8a 당김 계획 PR-A 시험 T-I).
#
# 다른 시험 파일은 mock provider 라 정책 문서의 json 을 지어내고(override 로
# 빈 문서), statement 입력만 본다. 여기서는 정책 문서를 진짜 provider 로
# 계산한다 — 정책 문서는 AWS 를 부르지 않고 로컬에서 json 을 만든다. 그 밖의
# 자원 · data 는 모두 override 라 provider 가 AWS 를 부르지 않는다(apply 도
# 지어낸 값으로 끝난다). 자격은 가짜 문자열이고 자격 · 계정 · 메타데이터 확인을
# 끈다 — 이 머신의 실제 자격을 찾아 쓰지 않게 한다. 자원을 더하면 여기에도
# override_resource 를 더한다(빠지면 apply 가 가짜 자격으로 AWS 를 불러
# 실패한다).
#
# 계정 ID · ARN · id 는 모두 가짜 값이다.

provider "aws" {
  region                      = "ap-northeast-2"
  access_key                  = "mock_access_key"
  secret_key                  = "mock_secret_key"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_region_validation      = true
  skip_requesting_account_id  = true
}

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

override_resource {
  target = aws_ssm_parameter.denylist
  values = {
    arn = "arn:aws:ssm:ap-northeast-2:123456789012:parameter/pokeclip/prod/media/ingest-auth-deny-tokens"
  }
}

override_resource {
  target = aws_iam_role.media
}

override_resource {
  target = aws_iam_instance_profile.media
}

override_resource {
  target = aws_iam_role_policy_attachment.managed
}

override_resource {
  target = aws_iam_role_policy.media
}

override_resource {
  target = aws_instance.this
}

override_resource {
  target = aws_lb_target_group_attachment.ingest
}

override_resource {
  target = aws_lb_target_group_attachment.http
}

override_resource {
  target = aws_security_group.media
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.media
}

override_resource {
  target = aws_vpc_security_group_egress_rule.media
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.rds_from_media
}

override_resource {
  target = aws_vpc_security_group_ingress_rule.auth_from_media
}

override_resource {
  target = aws_vpc_security_group_egress_rule.load_balancer
}

# 응답기 모드 · 목록 하나.
run "publish_statement_in_policy_json_with_stream" {
  command = apply

  variables {
    ingest_auth_image         = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/media-ingest-auth@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    stream_key_resolve_url    = "http://auth.pokeclip.internal:18086"
    lifecycle_publish_streams = ["01ARZ3NDEKTSV4RRFFQ69G5FAV"]
  }

  assert {
    condition     = length([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "BroadcastPublish"]) == 1
    error_message = "정책 JSON 에 BroadcastPublish 문이 정확히 하나가 아니다."
  }

  assert {
    condition = (
      one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "BroadcastPublish"]).Effect == "Allow" &&
      toset(flatten([one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "BroadcastPublish"]).Action])) == toset(["sns:Publish"]) &&
      flatten([one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "BroadcastPublish"]).Resource]) == [var.broadcast_topic_arn]
    )
    error_message = "BroadcastPublish 가 Allow · 동작 sns:Publish 하나 · Resource = 입력 주제 ARN 하나가 아니다."
  }

  # 기존 문(RewindDelete 포함)은 그대로이고 새 문은 하나만 더해진다.
  assert {
    condition     = sort([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement.Sid]) == tolist(["BroadcastPublish", "DbConnect", "DenylistRead", "EcrAuth", "EcrPull", "RewindBucket", "RewindDelete", "RewindObjects", "SecretsRead"])
    error_message = "정책 JSON 의 Sid 목록이 기존 여덟 + BroadcastPublish 가 아니다."
  }
}

# 빈 목록에서도 문을 둔다 — 끄기 · 켜기가 IAM 차분 없이 상자 교체 하나로 끝나게.
run "publish_statement_in_policy_json_without_stream" {
  command = apply

  assert {
    condition     = length([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "BroadcastPublish"]) == 1
    error_message = "정책 JSON 에 BroadcastPublish 문이 정확히 하나가 아니다."
  }

  assert {
    condition = (
      one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "BroadcastPublish"]).Effect == "Allow" &&
      toset(flatten([one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "BroadcastPublish"]).Action])) == toset(["sns:Publish"]) &&
      flatten([one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "BroadcastPublish"]).Resource]) == [var.broadcast_topic_arn]
    )
    error_message = "BroadcastPublish 가 Allow · 동작 sns:Publish 하나 · Resource = 입력 주제 ARN 하나가 아니다."
  }

  # 기존 문(RewindDelete 포함)은 그대로이고 새 문은 하나만 더해진다.
  assert {
    condition     = sort([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement.Sid]) == tolist(["BroadcastPublish", "DbConnect", "DenylistRead", "EcrAuth", "EcrPull", "RewindBucket", "RewindDelete", "RewindObjects", "SecretsRead"])
    error_message = "정책 JSON 의 Sid 목록이 기존 여덟 + BroadcastPublish 가 아니다."
  }
}
