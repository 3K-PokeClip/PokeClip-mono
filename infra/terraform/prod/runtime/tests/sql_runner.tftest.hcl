# SQL 실행기 스위치(설계 D7 · K3 · 계획 c8) — 꺼져 있으면 자원 · 읽기가 0 이고,
# 켜면 역할 · 프로필 · SG · 규칙 셋 · 인스턴스가 생긴다. 켰을 때 IAM 경로 ·
# IMDSv2 · 루트 볼륨 암호화 · 공인 IP 없음 · 별도 규칙 자원 · 정책 대상 하나 ·
# arm64 AMI 를 단언한다. AMI · 파티션 · 마스터 비밀은 가짜 값이다.
# override_data 가 network 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

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

override_data {
  target = data.aws_partition.current
  values = {
    partition = "aws"
  }
}

# 기본값은 꺼짐이다. 꺼지면 AMI 매개변수도 읽지 않는다(K3).
run "creates_nothing_when_disabled" {
  command = plan

  assert {
    condition     = var.sql_runner_enabled == false
    error_message = "sql_runner_enabled 기본값이 false 가 아니다(K3)."
  }

  # 자원 · data 를 통째로 참조하지 않고 첫 인스턴스의 표지 없는 속성 하나만
  # 가리킨다. 역할 · 인스턴스 · 파티션 · 매개변수는 provider 가 표지를 붙인
  # 속성(매개변수는 민감한 value)을 가져, 단언이 깨질 때 진단 출력에서
  # Terraform 1.16.4 가 「value has marks」 로 panic 한다(rewind_bucket 시험과
  # 같은 함정 — 펼침 [*] 도 통째 참조라 같다).
  assert {
    condition = alltrue([
      try(aws_iam_role.sql_runner[0].name, null) == null,
      try(aws_iam_role_policy_attachment.sql_runner_ssm[0].role, null) == null,
      try(aws_iam_role_policy.sql_runner_master_secret[0].name, null) == null,
      try(aws_iam_instance_profile.sql_runner[0].name, null) == null,
      try(aws_security_group.sql_runner[0].name, null) == null,
      try(aws_vpc_security_group_egress_rule.sql_runner_https[0].from_port, null) == null,
      try(aws_vpc_security_group_egress_rule.sql_runner_to_rds[0].from_port, null) == null,
      try(aws_vpc_security_group_ingress_rule.rds_from_sql_runner[0].from_port, null) == null,
      try(aws_instance.sql_runner[0].instance_type, null) == null,
    ])
    error_message = "꺼져 있는데 실행기 자원이 하나라도 있다."
  }

  assert {
    condition = (
      try(data.aws_ssm_parameter.al2023_arm64[0].name, null) == null &&
      try(data.aws_partition.current[0].partition, null) == null
    )
    error_message = "꺼져 있는데 실행기 data source 를 읽는다."
  }

  assert {
    condition     = output.sql_runner_instance_id == null
    error_message = "꺼져 있는데 출력 sql_runner_instance_id 가 null 이 아니다."
  }
}

run "puts_runner_role_and_profile_under_prod_path" {
  command = plan

  variables {
    sql_runner_enabled = true
  }

  assert {
    condition = (
      aws_iam_role.sql_runner[0].name == "pokeclip-prod-sql-runner" &&
      aws_iam_role.sql_runner[0].path == "/pokeclip/prod/"
    )
    error_message = "역할이 /pokeclip/prod/pokeclip-prod-sql-runner 가 아니다(IAM 경로 규약)."
  }

  assert {
    condition = (
      aws_iam_instance_profile.sql_runner[0].name == "pokeclip-prod-sql-runner" &&
      aws_iam_instance_profile.sql_runner[0].path == "/pokeclip/prod/" &&
      aws_iam_instance_profile.sql_runner[0].role == aws_iam_role.sql_runner[0].name
    )
    error_message = "인스턴스 프로필이 /pokeclip/prod/ 경로에서 실행기 역할을 담지 않는다."
  }

  assert {
    condition = jsondecode(aws_iam_role.sql_runner[0].assume_role_policy) == {
      Version = "2012-10-17"
      Statement = [
        {
          Effect    = "Allow"
          Principal = { Service = "ec2.amazonaws.com" }
          Action    = "sts:AssumeRole"
        },
      ]
    }
    error_message = "신뢰 정책이 EC2 의 sts:AssumeRole 한 문장이 아니다."
  }

  assert {
    condition     = aws_iam_role_policy_attachment.sql_runner_ssm[0].policy_arn == "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
    error_message = "SSM 관리형 정책 ARN 이 arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore 가 아니다."
  }

  assert {
    condition     = aws_iam_role_policy_attachment.sql_runner_ssm[0].role == aws_iam_role.sql_runner[0].name
    error_message = "SSM 관리형 정책이 실행기 역할에 붙지 않는다."
  }
}

# 관리형 정책 ARN 의 파티션은 리터럴이 아니라 data.aws_partition 에서 온다(R13).
run "builds_managed_policy_arn_from_partition" {
  command = plan

  variables {
    sql_runner_enabled = true
  }

  override_data {
    target = data.aws_partition.current
    values = {
      partition = "aws-example"
    }
  }

  assert {
    condition     = aws_iam_role_policy_attachment.sql_runner_ssm[0].policy_arn == "arn:aws-example:iam::aws:policy/AmazonSSMManagedInstanceCore"
    error_message = "SSM 관리형 정책 ARN 이 data.aws_partition 의 파티션을 따르지 않는다."
  }
}

# key_name 은 설정하지 않으면 provider 가 계산하는 속성이라 plan 단계에서
# 미지값이다. override 는 설정이 비운 속성만 채우므로, 표지값이 그대로 보이면
# 설정에 키가 없다는 뜻이다(rewind_bucket 시험과 같은 방식).
run "boots_arm64_box_with_imdsv2_and_no_public_ip" {
  command = plan

  variables {
    sql_runner_enabled = true
  }

  override_resource {
    target          = aws_instance.sql_runner
    override_during = plan
    values = {
      key_name = "unset-key-name-marker"
    }
  }

  assert {
    condition     = data.aws_ssm_parameter.al2023_arm64[0].name == "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
    error_message = "AMI 매개변수가 AL2023 arm64 기본 커널 이름이 아니다(결정 146)."
  }

  assert {
    condition     = aws_instance.sql_runner[0].ami == data.aws_ssm_parameter.al2023_arm64[0].insecure_value
    error_message = "인스턴스 AMI 가 AL2023 arm64 매개변수 값이 아니다."
  }

  assert {
    condition     = aws_instance.sql_runner[0].instance_type == "t4g.micro"
    error_message = "instance_type 이 t4g.micro 가 아니다(결정 146)."
  }

  assert {
    condition     = aws_instance.sql_runner[0].subnet_id == local.media_private_subnet_id
    error_message = "인스턴스가 network 의 media_private_subnet_id 에 있지 않다."
  }

  assert {
    condition     = aws_instance.sql_runner[0].associate_public_ip_address == false
    error_message = "associate_public_ip_address 가 false 가 아니다 — 공인 IP 가 붙는다."
  }

  assert {
    condition     = aws_instance.sql_runner[0].key_name == "unset-key-name-marker"
    error_message = "key_name 이 설정돼 있다 — 접속은 SSM 뿐이다."
  }

  assert {
    condition = (
      aws_instance.sql_runner[0].metadata_options[0].http_tokens == "required" &&
      aws_instance.sql_runner[0].metadata_options[0].http_endpoint == "enabled" &&
      aws_instance.sql_runner[0].metadata_options[0].http_put_response_hop_limit == 1
    )
    error_message = "IMDSv2 필수(http_tokens = required · http_endpoint = enabled · 홉 상한 1)가 아니다."
  }

  assert {
    condition     = aws_instance.sql_runner[0].instance_initiated_shutdown_behavior == "stop"
    error_message = "instance_initiated_shutdown_behavior 가 stop 이 아니다 — 상자 안에서 끈 종료가 인스턴스를 지우면 상태와 어긋난다."
  }

  assert {
    condition     = aws_instance.sql_runner[0].root_block_device[0].encrypted == true
    error_message = "루트 볼륨이 암호화되지 않는다."
  }

  assert {
    condition     = aws_instance.sql_runner[0].iam_instance_profile == aws_iam_instance_profile.sql_runner[0].name
    error_message = "인스턴스가 실행기 프로필을 쓰지 않는다."
  }

  assert {
    condition     = aws_instance.sql_runner[0].tags["Name"] == "pokeclip-prod-sql-runner"
    error_message = "인스턴스 Name 태그가 pokeclip-prod-sql-runner 가 아니다(잔여 확인의 표지)."
  }
}

# SG 는 규칙 0 이고(R3), 규칙은 별도 자원 셋이다 — 이그레스 443(SSM · 패키지 ·
# CA 번들) · 이그레스 5432 → RDS SG · RDS SG 인그레스 5432 ← 실행기 SG.
# 참조 SG id 는 계산 속성이라 apply 로 mock 이 값을 만들게 한다.
run "opens_only_https_and_postgres_rules" {
  command = apply

  variables {
    sql_runner_enabled = true
  }

  assert {
    condition = (
      aws_security_group.sql_runner[0].name == "pokeclip-prod-sql-runner" &&
      aws_security_group.sql_runner[0].vpc_id == local.vpc_id
    )
    error_message = "실행기 SG 가 network VPC 의 pokeclip-prod-sql-runner 가 아니다."
  }

  assert {
    condition     = toset(aws_instance.sql_runner[0].vpc_security_group_ids) == toset([aws_security_group.sql_runner[0].id])
    error_message = "인스턴스 SG 가 실행기 SG 하나가 아니다(VPC 기본 SG 금지)."
  }

  assert {
    condition = (
      aws_vpc_security_group_egress_rule.sql_runner_https[0].security_group_id == aws_security_group.sql_runner[0].id &&
      aws_vpc_security_group_egress_rule.sql_runner_https[0].ip_protocol == "tcp" &&
      aws_vpc_security_group_egress_rule.sql_runner_https[0].from_port == 443 &&
      aws_vpc_security_group_egress_rule.sql_runner_https[0].to_port == 443 &&
      aws_vpc_security_group_egress_rule.sql_runner_https[0].cidr_ipv4 == "0.0.0.0/0"
    )
    error_message = "실행기 이그레스가 tcp 443 → 0.0.0.0/0 이 아니다."
  }

  assert {
    condition = (
      aws_vpc_security_group_egress_rule.sql_runner_to_rds[0].security_group_id == aws_security_group.sql_runner[0].id &&
      aws_vpc_security_group_egress_rule.sql_runner_to_rds[0].ip_protocol == "tcp" &&
      aws_vpc_security_group_egress_rule.sql_runner_to_rds[0].from_port == 5432 &&
      aws_vpc_security_group_egress_rule.sql_runner_to_rds[0].to_port == 5432 &&
      aws_vpc_security_group_egress_rule.sql_runner_to_rds[0].referenced_security_group_id == aws_security_group.rds.id &&
      aws_vpc_security_group_egress_rule.sql_runner_to_rds[0].cidr_ipv4 == null
    )
    error_message = "실행기 이그레스가 tcp 5432 → RDS SG 가 아니다."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.rds_from_sql_runner[0].security_group_id == aws_security_group.rds.id &&
      aws_vpc_security_group_ingress_rule.rds_from_sql_runner[0].ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.rds_from_sql_runner[0].from_port == 5432 &&
      aws_vpc_security_group_ingress_rule.rds_from_sql_runner[0].to_port == 5432 &&
      aws_vpc_security_group_ingress_rule.rds_from_sql_runner[0].referenced_security_group_id == aws_security_group.sql_runner[0].id &&
      aws_vpc_security_group_ingress_rule.rds_from_sql_runner[0].cidr_ipv4 == null
    )
    error_message = "RDS SG 인그레스가 tcp 5432 ← 실행기 SG 가 아니다."
  }
}

# 인라인 정책은 마스터 비밀 하나의 GetSecretValue 한 동작뿐이다. 비밀 ARN 은
# mock 이 비워 두므로 형식을 갖춘 가짜 값으로 채운다(계정 자리는 비운다).
# 앞 apply 시험이 남긴 상태에는 빈 블록의 RDS 가 이미 있어 override 가 닿지
# 않으므로 상태를 따로 쓴다(state_key).
run "reads_only_master_user_secret" {
  command   = apply
  state_key = "master_user_secret"

  variables {
    sql_runner_enabled = true
  }

  override_resource {
    target = aws_db_instance.this
    values = {
      master_user_secret = [
        {
          kms_key_id    = "example-kms-key"
          secret_arn    = "arn:aws:secretsmanager:ap-northeast-2::secret:rds!db-example"
          secret_status = "active"
        },
      ]
    }
  }

  assert {
    condition = jsondecode(aws_iam_role_policy.sql_runner_master_secret[0].policy) == {
      Version = "2012-10-17"
      Statement = [
        {
          Sid      = "ReadRdsMasterUserSecret"
          Effect   = "Allow"
          Action   = "secretsmanager:GetSecretValue"
          Resource = "arn:aws:secretsmanager:ap-northeast-2::secret:rds!db-example"
        },
      ]
    }
    error_message = "인라인 정책이 마스터 비밀 하나의 secretsmanager:GetSecretValue 한 문장이 아니다."
  }

  assert {
    condition     = jsondecode(aws_iam_role_policy.sql_runner_master_secret[0].policy).Statement[0].Resource == output.rds_master_user_secret_arn
    error_message = "인라인 정책의 대상이 출력 rds_master_user_secret_arn 과 다르다."
  }

  assert {
    condition = (
      aws_iam_role_policy.sql_runner_master_secret[0].name == "pokeclip-prod-sql-runner-master-secret" &&
      aws_iam_role_policy.sql_runner_master_secret[0].role == aws_iam_role.sql_runner[0].name
    )
    error_message = "인라인 정책 이름 · 붙는 역할이 다르다."
  }

  assert {
    condition     = output.sql_runner_instance_id == aws_instance.sql_runner[0].id
    error_message = "출력 sql_runner_instance_id 가 실행기 인스턴스 id 가 아니다."
  }
}
