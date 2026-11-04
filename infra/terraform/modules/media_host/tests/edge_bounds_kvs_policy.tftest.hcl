# Media 인스턴스 역할의 /dvr 하한 KVS 권한(POK-525 — 문 EdgeBoundsKvs)을 정책
# JSON(정책 문서가 실제로 낸 json)에서 단언한다.
#
# 정책 문서를 진짜 provider 로 계산하는 까닭 · 가짜 자격 · override 규약은
# broadcast_publish_policy.tftest.hcl 머리 주석과 같다. 자원을 더하면 두 파일
# 모두에 override_resource 를 더한다(빠지면 apply 가 가짜 자격으로 AWS 를 불러
# 실패한다).
#
# 두 run 의 assert 다섯은 일부러 같다 — Writer 스위치가 IAM 을 바꾸지 않는다는
# 것을 두 상태에서 본다(run 끼리 식을 나눌 수단이 없다 — 같은 머리 주석).
#
# 크기 assert 의 한도는 IAM 할당량이다 — 역할 하나의 인라인 정책 합은
# 10,240자를 넘을 수 없고 공백은 세지 않는다(「IAM and AWS STS quotas」
# https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_iam-quotas.html
# 대조 2026-11-04). 이 역할의 인라인 정책은 aws_iam_role_policy.media 하나다
# (관리형 붙임은 따로 센다). 값은 아래 가짜 입력으로 낸 정책이다 — 실제 입력
# (비밀 ARN 목록 등)이 길면 그만큼 커진다.
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
  edge_bounds_kvs_arn           = "arn:aws:cloudfront::123456789012:key-value-store/00000000-0000-0000-0000-0000000000e1"
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


# Writer 끔(기본) — 문은 둔다. 켜기 · 끄기가 IAM 차분 없이 상자 교체 하나로
# 끝나게 한다(BroadcastPublish 와 같은 꼴).
run "edge_bounds_statement_in_policy_json_writer_off" {
  command = apply

  assert {
    condition     = length([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "EdgeBoundsKvs"]) == 1
    error_message = "정책 JSON 에 EdgeBoundsKvs 문이 정확히 하나가 아니다."
  }

  assert {
    condition = (
      one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "EdgeBoundsKvs"]).Effect == "Allow" &&
      toset(flatten([one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "EdgeBoundsKvs"]).Action])) == toset(["cloudfront-keyvaluestore:DescribeKeyValueStore", "cloudfront-keyvaluestore:GetKey", "cloudfront-keyvaluestore:UpdateKeys"]) &&
      flatten([one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "EdgeBoundsKvs"]).Resource]) == [var.edge_bounds_kvs_arn]
    )
    error_message = "EdgeBoundsKvs 가 Allow · 동작 셋(Describe · GetKey · UpdateKeys) · Resource = 입력 KVS ARN 하나가 아니다."
  }

  # 정책 전체의 KVS 값 동작이 그 셋뿐이다 — ListKeys · PutKey · DeleteKey ·
  # 와일드카드가 다른 문으로 새지 않는다.
  assert {
    condition     = toset(flatten([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : [for action in flatten([statement.Action]) : action if startswith(action, "cloudfront-keyvaluestore:")]])) == toset(["cloudfront-keyvaluestore:DescribeKeyValueStore", "cloudfront-keyvaluestore:GetKey", "cloudfront-keyvaluestore:UpdateKeys"])
    error_message = "정책 JSON 의 cloudfront-keyvaluestore 동작이 셋(Describe · GetKey · UpdateKeys)이 아니다."
  }

  assert {
    condition     = length(flatten([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : [for action in flatten([statement.Action]) : action if startswith(action, "cloudfront:")]])) == 0
    error_message = "정책 JSON 에 CloudFront 제어면 동작이 있다 — 상자는 KVS 그릇을 만들거나 바꾸지 않는다."
  }

  # 문을 더해도 역할 인라인 정책 한도(10,240자 · 공백은 세지 않음) 밑이다.
  assert {
    condition     = length(replace(aws_iam_role_policy.media.policy, "/\\s/", "")) < 10240
    error_message = "정책 JSON(공백 뺌)이 10240자 미만이 아니다 — 역할 인라인 정책 한도를 넘는다."
  }
}

# Writer 켬 — IAM 은 끔과 같다(바뀌는 것은 사이드카 env 하나).
run "edge_bounds_statement_in_policy_json_writer_on" {
  command = apply

  variables {
    edge_bounds_writer_enabled = true
  }

  assert {
    condition     = length([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "EdgeBoundsKvs"]) == 1
    error_message = "정책 JSON 에 EdgeBoundsKvs 문이 정확히 하나가 아니다."
  }

  assert {
    condition = (
      one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "EdgeBoundsKvs"]).Effect == "Allow" &&
      toset(flatten([one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "EdgeBoundsKvs"]).Action])) == toset(["cloudfront-keyvaluestore:DescribeKeyValueStore", "cloudfront-keyvaluestore:GetKey", "cloudfront-keyvaluestore:UpdateKeys"]) &&
      flatten([one([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : statement if statement.Sid == "EdgeBoundsKvs"]).Resource]) == [var.edge_bounds_kvs_arn]
    )
    error_message = "EdgeBoundsKvs 가 Allow · 동작 셋(Describe · GetKey · UpdateKeys) · Resource = 입력 KVS ARN 하나가 아니다."
  }

  assert {
    condition     = toset(flatten([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : [for action in flatten([statement.Action]) : action if startswith(action, "cloudfront-keyvaluestore:")]])) == toset(["cloudfront-keyvaluestore:DescribeKeyValueStore", "cloudfront-keyvaluestore:GetKey", "cloudfront-keyvaluestore:UpdateKeys"])
    error_message = "정책 JSON 의 cloudfront-keyvaluestore 동작이 셋(Describe · GetKey · UpdateKeys)이 아니다."
  }

  assert {
    condition     = length(flatten([for statement in jsondecode(aws_iam_role_policy.media.policy).Statement : [for action in flatten([statement.Action]) : action if startswith(action, "cloudfront:")]])) == 0
    error_message = "정책 JSON 에 CloudFront 제어면 동작이 있다 — 상자는 KVS 그릇을 만들거나 바꾸지 않는다."
  }

  # 문을 더해도 역할 인라인 정책 한도(10,240자 · 공백은 세지 않음) 밑이다.
  assert {
    condition     = length(replace(aws_iam_role_policy.media.policy, "/\\s/", "")) < 10240
    error_message = "정책 JSON(공백 뺌)이 10240자 미만이 아니다 — 역할 인라인 정책 한도를 넘는다."
  }
}
