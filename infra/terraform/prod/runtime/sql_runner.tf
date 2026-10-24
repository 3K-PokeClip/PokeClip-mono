# SQL 실행기 스위치(설계 D7 · K3) — media DB 계정 · stream_* 스키마 SQL(sql/)을
# RDS 에 적용하는 동안만 켜는 작은 EC2 상자다. 접속은 SSM send-command 뿐이고
# (키 · 공인 IP · 인그레스 없음), 절차는 runbooks/runtime-1a.md 다.
#
# 켜면 생기는 것: 역할 · 인스턴스 프로필(/pokeclip/prod/) · SG · 규칙 셋 ·
# 인스턴스. 끄면 전부 지워지고 AMI 매개변수도 읽지 않는다. 역할은 SSM 관리형
# 정책과 관리형 마스터 비밀 하나의 GetSecretValue 만 가진다.
#
# RDS SG ← 실행기 5432 규칙은 켜진 동안만 이 루트가 가진다 — RDS SG 규칙은 소비
# 루트가 만든다는 계약의 유일한 예외다(CONTRACT-runtime.md 「출력과 소유
# 경계」).
#
# 보안 메모: 켜진 동안 iam:PassRole 을 가진 dev 주체가 이 프로필로 상자를 띄워
# 마스터 비밀을 읽을 수 있다. 그래서 SQL 직후 끈다. 켜기 전 2-3 판정은
# runtime-1a 묶음으로 principal-check.sh 가 한다(runbooks/runtime-1a.md 1).

variable "sql_runner_enabled" {
  description = "SQL 실행기(EC2 · 역할 · SG · RDS 5432 규칙)를 켠다. SQL 적용 동안만 true 로 두고 끝나면 false 로 되돌린다(runbooks/runtime-1a.md)."
  type        = bool
  default     = false
}

locals {
  sql_runner_count = var.sql_runner_enabled ? 1 : 0
  sql_runner_name  = "pokeclip-prod-sql-runner"
}

data "aws_partition" "current" {
  count = local.sql_runner_count
}

# AL2023 arm64 최신 AMI(결정 146). 값은 비밀이 아니라 insecure_value 를 쓴다
# — value 는 민감 표지가 붙어 plan 에 AMI 가 보이지 않는다.
data "aws_ssm_parameter" "al2023_arm64" {
  count = local.sql_runner_count

  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

resource "aws_iam_role" "sql_runner" {
  count = local.sql_runner_count

  name = local.sql_runner_name
  path = "/pokeclip/prod/"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "ec2.amazonaws.com" }
        Action    = "sts:AssumeRole"
      },
    ]
  })
}

# SSM 에이전트 등록 · send-command 수신.
resource "aws_iam_role_policy_attachment" "sql_runner_ssm" {
  count = local.sql_runner_count

  role       = aws_iam_role.sql_runner[0].name
  policy_arn = "arn:${data.aws_partition.current[0].partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 관리형 마스터 비밀은 AWS 관리 키(aws/secretsmanager)라 kms 권한이 따로
# 필요 없다(rds.tf 머리 주석).
resource "aws_iam_role_policy" "sql_runner_master_secret" {
  count = local.sql_runner_count

  name = "${local.sql_runner_name}-master-secret"
  role = aws_iam_role.sql_runner[0].name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadRdsMasterUserSecret"
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = one(aws_db_instance.this.master_user_secret[*].secret_arn)
      },
    ]
  })
}

resource "aws_iam_instance_profile" "sql_runner" {
  count = local.sql_runner_count

  name = local.sql_runner_name
  path = "/pokeclip/prod/"
  role = aws_iam_role.sql_runner[0].name
}

resource "aws_security_group" "sql_runner" {
  count = local.sql_runner_count

  name        = local.sql_runner_name
  description = "pokeclip prod SQL runner - SSM only, no ingress"
  vpc_id      = local.vpc_id
}

# SSM 끝점 · 패키지 저장소 · RDS CA 번들은 모두 HTTPS 다(NAT 경유).
resource "aws_vpc_security_group_egress_rule" "sql_runner_https" {
  count = local.sql_runner_count

  security_group_id = aws_security_group.sql_runner[0].id
  description       = "HTTPS - SSM, packages, RDS CA bundle"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "sql_runner_to_rds" {
  count = local.sql_runner_count

  security_group_id            = aws_security_group.sql_runner[0].id
  description                  = "PostgreSQL to RDS"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.rds.id
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_sql_runner" {
  count = local.sql_runner_count

  security_group_id            = aws_security_group.rds.id
  description                  = "PostgreSQL from SQL runner (only while enabled)"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.sql_runner[0].id
}

resource "aws_instance" "sql_runner" {
  count = local.sql_runner_count

  ami                         = data.aws_ssm_parameter.al2023_arm64[0].insecure_value
  instance_type               = "t4g.micro"
  subnet_id                   = local.media_private_subnet_id
  vpc_security_group_ids      = [aws_security_group.sql_runner[0].id]
  iam_instance_profile        = aws_iam_instance_profile.sql_runner[0].name
  associate_public_ip_address = false

  # 상자 안에서 끄면(런북의 자동 종료 예약 포함) 지우지 않고 멈춘다. 지우면
  # 상태와 어긋나 다음 plan 이 실행기를 다시 만들려 한다. 자원 정리는 여전히
  # sql_runner_enabled = false 다.
  instance_initiated_shutdown_behavior = "stop"

  # IMDSv2 필수 — SSM 에이전트가 역할 자격을 받는 길이라 끝점은 켠다. 홉 상한 1
  # 은 상자 밖(컨테이너 · 전달)으로 토큰 응답이 나가지 않게 한다.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    encrypted   = true
    volume_type = "gp3"
  }

  tags = {
    Name = local.sql_runner_name
  }
}

output "sql_runner_instance_id" {
  description = "SQL 실행기 인스턴스 id — 운영 전용(send-command 대상). 꺼져 있으면 null."
  value       = one(aws_instance.sql_runner[*].id)
}
