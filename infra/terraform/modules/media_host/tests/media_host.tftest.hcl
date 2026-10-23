# Media 상자 모듈(계획 M9 c1 · 설계 3-3 · 3-3-1 · 5-3 · 5-5)을 mock provider 로
# 단언한다 — 인스턴스(서브넷 · 공인 IP · IMDS · AMI) · Media SG 인그레스 ·
# 이그레스 · RDS 와 로드밸런서 쪽 짝 규칙 · 인스턴스 역할 정책 · 관리형 정책 ·
# 대상 등록 · 응답기 거부 목록 SSM 파라미터.
#
# 계정 ID · ARN · id 는 모두 가짜 값이다. 정책 문서(data source)의 json 은 mock
# 이 지어내므로 statement 입력을 직접 본다. lifecycle(ignore_changes ·
# prevent_destroy)은 plan 결과에 나오지 않아 여기서 볼 수 없다(README 「시험」).

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
  media_secret_arns             = ["arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/internal-api-token-MnOpQr"]
  docker_version                = "25.0.16"
  ecr_credential_helper_version = "0.12.0-3.amzn2023"
  compose_version               = "5.5.0"
  compose_sha256                = "ff42489f5a9b879d5d117c5ffea6defc27390b3286da8ad52cbc9c6ab5df590e"
}

run "places_instance_in_media_private_subnet_without_public_ip" {
  command = plan

  assert {
    condition     = aws_instance.this["a"].subnet_id == "subnet-0media0private000"
    error_message = "Media 상자가 media_private_subnet_id 에 있지 않다."
  }

  assert {
    condition     = aws_instance.this["a"].associate_public_ip_address == false
    error_message = "Media 상자에 공인 IP 가 붙는다."
  }

  assert {
    condition     = aws_instance.this["a"].ami == "ami-0123456789abcdef0" && aws_instance.this["a"].instance_type == "c8gn.large"
    error_message = "AMI 가 SSM 공개 파라미터 값이 아니거나 instance_type 이 입력과 다르다."
  }

  assert {
    condition     = data.aws_ssm_parameter.ami.name == "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
    error_message = "AMI 파라미터가 AL2023 arm64(Graviton) 공개 파라미터가 아니다."
  }

  assert {
    condition = (
      aws_instance.this["a"].root_block_device[0].encrypted &&
      aws_instance.this["a"].root_block_device[0].delete_on_termination &&
      aws_instance.this["a"].root_block_device[0].volume_type == "gp3" &&
      aws_instance.this["a"].root_block_device[0].volume_size == 150 &&
      aws_instance.this["a"].root_block_device[0].throughput == 125
    )
    error_message = "루트 볼륨이 gp3 · 암호화 · 종료 때 삭제 · root_volume_size · 처리량 기본 125 가 아니다."
  }

  assert {
    condition     = aws_instance.this["a"].tags["Name"] == "pokeclip-prod-media-a" && aws_instance.this["a"].tags["ConfigVersion"] == "1"
    error_message = "인스턴스 Name · ConfigVersion 태그가 <name>-media-<키> · config_version 이 아니다."
  }
}

run "sets_root_volume_throughput" {
  command = plan

  variables {
    root_volume_throughput = 250
  }

  assert {
    condition     = aws_instance.this["a"].root_block_device[0].throughput == 250
    error_message = "루트 볼륨 처리량이 root_volume_throughput 이 아니다."
  }
}

# 설계 3-3-1 · 계획 G22: 컨테이너가 인스턴스 역할 자격을 얻도록 hop limit 2.
run "requires_imdsv2_with_hop_limit_two" {
  command = plan

  assert {
    condition = (
      aws_instance.this["a"].metadata_options[0].http_endpoint == "enabled" &&
      aws_instance.this["a"].metadata_options[0].http_tokens == "required" &&
      aws_instance.this["a"].metadata_options[0].http_put_response_hop_limit == 2
    )
    error_message = "IMDS 가 v2 필수 · hop limit 2 가 아니다."
  }
}

run "keys_instances_by_config" {
  command = plan

  variables {
    instances = {
      a = { config_version = "1" }
      b = { config_version = "2" }
    }
  }

  assert {
    condition     = toset(keys(aws_instance.this)) == toset(["a", "b"])
    error_message = "인스턴스 키 집합이 instances 키와 다르다."
  }

  # 새 구성은 새 키로만 들어간다(5-3) — 구성 판이 다르면 user_data 도 다르다.
  assert {
    condition     = aws_instance.this["a"].user_data_base64 != aws_instance.this["b"].user_data_base64
    error_message = "config_version 이 다른 두 키의 user_data 가 같다."
  }
}

# 설계 5-5 Media SG 표.
run "media_sg_ingress_matches_design" {
  command = plan

  assert {
    condition     = aws_security_group.media.name == "pokeclip-prod-media" && aws_security_group.media.vpc_id == "vpc-0123456789abcdef0"
    error_message = "Media SG 이름 · VPC 가 다르다."
  }

  assert {
    condition = {
      for key, rule in aws_vpc_security_group_ingress_rule.media :
      key => [rule.ip_protocol, rule.from_port, rule.to_port, rule.referenced_security_group_id]
      } == {
      srt        = ["udp", 8890, 8890, "sg-0nlb0000000000000"]
      live       = ["tcp", 18081, 18081, "sg-0internalalb000000"]
      playback   = ["tcp", 18082, 18082, "sg-0internalalb000000"]
      control    = ["tcp", 18083, 18083, "sg-0internalalb000000"]
      health_alb = ["tcp", 18084, 18084, "sg-0internalalb000000"]
      health_nlb = ["tcp", 18084, 18084, "sg-0nlb0000000000000"]
    }
    error_message = "Media SG 인그레스가 5-5 표(SRT ← NLB · 프록시 셋 ← 내부 ALB · 헬스 ← 둘)와 다르다."
  }

  assert {
    condition     = alltrue([for rule in aws_vpc_security_group_ingress_rule.media : rule.cidr_ipv4 == null])
    error_message = "Media SG 인그레스에 CIDR 출처가 있다."
  }

  # MediaMTX 루프백 포트는 SG 로 열지 않는다.
  assert {
    condition = length(setintersection(
      toset([for rule in aws_vpc_security_group_ingress_rule.media : rule.from_port]),
      toset([8888, 9996, 9997]),
    )) == 0
    error_message = "Media SG 인그레스에 8888 · 9996 · 9997 이 있다."
  }
}

run "media_sg_egress_and_rds_pair" {
  command = plan

  assert {
    # 상대 칸 셋 중 비어 있는 칸은 "-" 로 적는다.
    condition = {
      for key, rule in aws_vpc_security_group_egress_rule.media :
      key => join(" ", [
        rule.ip_protocol, rule.from_port, rule.to_port,
        coalesce(rule.prefix_list_id, "-"), coalesce(rule.cidr_ipv4, "-"), coalesce(rule.referenced_security_group_id, "-"),
      ])
      } == {
      https_s3  = "tcp 443 443 pl-0123456789abcdef0 - -"
      https_any = "tcp 443 443 - 0.0.0.0/0 -"
      postgres  = "tcp 5432 5432 - - sg-0rds0000000000000"
    }
    error_message = "Media SG 이그레스가 443 → S3 prefix list · 443 → 0.0.0.0/0 · 5432 → rds_sg_id 가 아니다."
  }

  # kty 21차: RDS 인그레스 ← Media 5432 는 이그레스와 같은 루트의 한 쌍.
  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.rds_from_media.security_group_id == "sg-0rds0000000000000" &&
      aws_vpc_security_group_ingress_rule.rds_from_media.ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.rds_from_media.from_port == 5432 &&
      aws_vpc_security_group_ingress_rule.rds_from_media.to_port == 5432
    )
    error_message = "RDS SG 인그레스 ← Media 5432 가 rds_sg_id 에 없다."
  }
}

# 5-5: 로드밸런서 → Media 이그레스는 media 가 소유한다.
run "load_balancer_egress_to_media" {
  command = plan

  assert {
    condition = {
      for key, rule in aws_vpc_security_group_egress_rule.load_balancer :
      key => [rule.security_group_id, rule.ip_protocol, rule.from_port]
      } == {
      alb_live     = ["sg-0internalalb000000", "tcp", 18081]
      alb_playback = ["sg-0internalalb000000", "tcp", 18082]
      alb_control  = ["sg-0internalalb000000", "tcp", 18083]
      alb_health   = ["sg-0internalalb000000", "tcp", 18084]
      nlb_srt      = ["sg-0nlb0000000000000", "udp", 8890]
      nlb_health   = ["sg-0nlb0000000000000", "tcp", 18084]
    }
    error_message = "로드밸런서 → Media 이그레스가 5-5(내부 ALB 넷 · NLB 둘)와 다르다."
  }
}

run "wires_sg_references_to_media_sg" {
  command = apply

  assert {
    condition = alltrue([
      for rule in concat(values(aws_vpc_security_group_ingress_rule.media), values(aws_vpc_security_group_egress_rule.media)) :
      rule.security_group_id == aws_security_group.media.id
    ])
    error_message = "Media SG 규칙이 Media SG 가 아닌 SG 에 붙었다."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.rds_from_media.referenced_security_group_id == aws_security_group.media.id
    error_message = "RDS 인그레스의 출처가 Media SG 가 아니다."
  }

  assert {
    condition = alltrue([
      for rule in aws_vpc_security_group_egress_rule.load_balancer :
      rule.referenced_security_group_id == aws_security_group.media.id
    ])
    error_message = "로드밸런서 이그레스의 상대가 Media SG 가 아니다."
  }

  assert {
    condition     = aws_instance.this["a"].vpc_security_group_ids == toset([aws_security_group.media.id])
    error_message = "Media 상자에 Media SG 하나만 붙지 않았다."
  }

  assert {
    condition     = output.media_sg_id == aws_security_group.media.id
    error_message = "출력 media_sg_id 가 Media SG 가 아니다."
  }
}

# 3절 이름 규약 · 설계 2-3-15: prod 역할 · 인스턴스 프로필의 경로.
run "role_and_profile_use_prod_path" {
  command = plan

  assert {
    condition     = aws_iam_role.media.path == "/pokeclip/prod/" && aws_iam_instance_profile.media.path == "/pokeclip/prod/"
    error_message = "Media 인스턴스 역할 · 인스턴스 프로필의 path 가 /pokeclip/prod/ 가 아니다."
  }

  assert {
    condition     = aws_iam_role.media.name == "pokeclip-prod-media" && aws_iam_instance_profile.media.name == "pokeclip-prod-media"
    error_message = "역할 · 인스턴스 프로필 이름이 <name>-media 가 아니다."
  }

  assert {
    condition = (
      jsondecode(aws_iam_role.media.assume_role_policy).Statement[0].Principal.Service == "ec2.amazonaws.com" &&
      jsondecode(aws_iam_role.media.assume_role_policy).Statement[0].Action == "sts:AssumeRole"
    )
    error_message = "신뢰 정책이 EC2 서비스 AssumeRole 이 아니다."
  }

  assert {
    condition     = keys(aws_iam_role_policy_attachment.managed) == ["AmazonSSMManagedInstanceCore"]
    error_message = "관리형 정책 연결 키 집합이 {AmazonSSMManagedInstanceCore} 가 아니다."
  }

  assert {
    condition     = aws_iam_role_policy_attachment.managed["AmazonSSMManagedInstanceCore"].policy_arn == "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
    error_message = "SSM 관리형 정책 ARN 이 다르다."
  }
}

# 거부 목록 파라미터 ARN 은 계산 속성이라 apply 로 돌린다.
run "instance_role_policy_statements" {
  command = apply

  # sid → [동작 집합, Resource 집합].
  assert {
    condition = {
      for statement in data.aws_iam_policy_document.media.statement :
      statement.sid => toset(statement.resources)
      if statement.sid != "DenylistRead"
      } == {
      SecretsRead = toset([
        "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/cdn-secret-AbCdEf",
        "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/test-stream-key-GhIjKl",
        "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:pokeclip/prod/internal-api-token-MnOpQr",
      ])
      EcrAuth = toset(["*"])
      EcrPull = toset([
        for key in ["media-nginx", "media-mediamtx", "media-sidecar", "media-ingest-auth", "media-loopback-sender", "media-host-bundle"] :
        "arn:aws:ecr:ap-northeast-2:123456789012:repository/pokeclip-prod/${key}"
      ])
      DbConnect = toset([
        "arn:aws:rds-db:ap-northeast-2:123456789012:dbuser:db-EXAMPLE0123456789ABCDEFGH/media_indexer",
        "arn:aws:rds-db:ap-northeast-2:123456789012:dbuser:db-EXAMPLE0123456789ABCDEFGH/media_janitor",
      ])
      RewindObjects = toset(["arn:aws:s3:::pokeclip-prod-rewind-example/*"])
      RewindBucket  = toset(["arn:aws:s3:::pokeclip-prod-rewind-example"])
    }
    error_message = "인스턴스 역할 정책의 statement · Resource 가 계획 M9 c1 과 다르다."
  }

  assert {
    condition = {
      for statement in data.aws_iam_policy_document.media.statement :
      statement.sid => toset(statement.actions)
      } == {
      SecretsRead   = toset(["secretsmanager:GetSecretValue"])
      EcrAuth       = toset(["ecr:GetAuthorizationToken"])
      EcrPull       = toset(["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"])
      DbConnect     = toset(["rds-db:connect"])
      RewindObjects = toset(["s3:GetObject", "s3:PutObject"])
      RewindBucket  = toset(["s3:ListBucket"])
      DenylistRead  = toset(["ssm:GetParameter"])
    }
    error_message = "인스턴스 역할 정책의 동작이 다르다."
  }

  # `*` 는 GetAuthorizationToken 문과 rewind 버킷 객체(`<버킷>/*`)에만.
  assert {
    condition = alltrue([
      for statement in data.aws_iam_policy_document.media.statement :
      contains(["EcrAuth", "RewindObjects"], statement.sid) ||
      alltrue([for resource in statement.resources : !strcontains(resource, "*")])
    ])
    error_message = "GetAuthorizationToken · rewind 객체 밖의 문에 와일드카드 Resource 가 있다."
  }

  assert {
    condition     = aws_iam_role_policy.media.role == aws_iam_role.media.name || aws_iam_role_policy.media.role == aws_iam_role.media.id
    error_message = "인라인 정책이 Media 역할에 붙지 않았다."
  }
}

run "denylist_parameter_and_read_policy" {
  command = apply

  assert {
    condition = (
      aws_ssm_parameter.denylist.name == "/pokeclip/prod/media/ingest-auth-deny-tokens" &&
      aws_ssm_parameter.denylist.type == "String" &&
      aws_ssm_parameter.denylist.tier == "Standard" &&
      aws_ssm_parameter.denylist.value == "# pokeclip deny-tokens v1"
    )
    error_message = "거부 목록 파라미터가 이름 · String · Standard · 초기값 한 줄이 아니다."
  }

  # String 이라 KMS 동작이 없고, Resource 는 그 파라미터 ARN 하나뿐이다.
  assert {
    condition = one([
      for statement in data.aws_iam_policy_document.media.statement :
      statement.resources if statement.sid == "DenylistRead"
    ]) == toset([aws_ssm_parameter.denylist.arn])
    error_message = "ssm:GetParameter 의 Resource 가 거부 목록 파라미터 ARN 하나가 아니다."
  }
}

# 5-3: 대상 그룹 넷에는 활성 키의 상자 하나만.
run "registers_active_instances" {
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
    condition = (
      aws_lb_target_group_attachment.ingest.target_group_arn == var.target_group_arns.ingest &&
      aws_lb_target_group_attachment.ingest.target_id == aws_instance.this["b"].id &&
      aws_lb_target_group_attachment.ingest.port == 8890
    )
    error_message = "NLB ingest 대상이 instances[ingest_active_key] · SRT 포트가 아니다."
  }

  assert {
    condition = {
      for key, attachment in aws_lb_target_group_attachment.http :
      key => [attachment.target_group_arn, attachment.target_id, attachment.port]
      } == {
      live     = [var.target_group_arns.live, aws_instance.this["a"].id, 18081]
      control  = [var.target_group_arns.control, aws_instance.this["a"].id, 18083]
      playback = [var.target_group_arns.playback, aws_instance.this["a"].id, 18082]
    }
    error_message = "ALB 대상 그룹 셋이 instances[http_active_key] · 각 프록시 포트가 아니다."
  }

  assert {
    condition     = output.instance_ids == { for key, instance in aws_instance.this : key => instance.id }
    error_message = "출력 instance_ids 가 인스턴스 id 맵이 아니다."
  }

  assert {
    condition     = output.instance_role_arn == aws_iam_role.media.arn
    error_message = "출력 instance_role_arn 이 Media 역할이 아니다."
  }
}
