# POK-304 prod RDS PostgreSQL 17(설계 D1 · D2). 사이드카 · janitor 는 IAM DB
# 인증으로 접속한다. 앱 서버 넷도 IAM DB 인증이 기본이고(대체 갈래는 비밀번호),
# 사용자는 sql/10_roles.sql 이 만들며 출력 app_db_users 가 그 이름을 낸다
# (POK-407). 이 파일은 RDS 와 그 전용 SG 만 만들고, SG 규칙 · 접속 정책은 소비
# 루트가 만든다(CONTRACT-runtime.md 「출력과 소유 경계」).
#
# 판은 메이저만 고정한다(H4). provider 는 설정한 판이 실제 판의 점 경계
# 접두이면("17" 과 "17.6") 차이로 보지 않는다 — internal/service/rds/verify.go
# 의 compareActualEngineVersion(v6.19.0 · 잠금 판 v6.68.0 대조). RDS 는 메이저만
# 받으면 그 메이저의 최근 부판으로 만든다. 그래서 AWS 가 부판을 올려도(자동 ·
# 보안 · 지원 종료 강제) 다음 plan 이 옛 부판으로 되돌리려 하지 않는다. 실제
# 부판은 engine_version_actual 에 있고 운영 확인 줄에 기록한다.
# ignore_changes = [engine_version] 는 코드가 실제 판에 대해 거짓말을 하게 하고
# 의도한 메이저 업그레이드 때 lifecycle 을 다시 풀어야 해서 기각했다.
#
# 마스터 비밀번호는 RDS 관리형 비밀(manage_master_user_password)이라 상태에 값이
# 없다. 비밀 이름은 RDS 가 정한다(rds!db-* — 이름 규약의 예외). 암호화 키는
# AWS 관리 키 aws/secretsmanager 를 쓴다. CMK 는 키 정책 · 비용 · 삭제 대기라는
# 표면을 더하고, 관리 키면 SQL 실행기 역할에 kms 권한을 따로 줄 필요가 없어
# 기각했다.

locals {
  # 로그 그룹 이름이 이 값을 따라야 해서 한 곳에 둔다.
  rds_identifier = "pokeclip-prod-db"
}

resource "aws_security_group" "rds" {
  name        = "pokeclip-prod-rds"
  description = "pokeclip prod RDS - rules are owned by consumer roots"
  vpc_id      = local.vpc_id
}

resource "aws_db_subnet_group" "this" {
  name       = "pokeclip-prod-db"
  subnet_ids = local.private_subnet_ids
}

# 두 파라미터는 RDS 파라미터 표에서 Dynamic 이다(force_ssl 의 apply_method 는 아래 주석).
resource "aws_db_parameter_group" "this" {
  name   = "pokeclip-prod-pg17"
  family = "postgres17"

  # TLS 를 강제한다(17 은 기본값도 1 이지만 계약으로 못 박는다).
  parameter {
    name  = "rds.force_ssl"
    value = "1"
    # 값이 엔진 기본과 같아 AWS 가 source=system · pending-reboot 로 돌려준다 — immediate 면 plan 이 늘 바뀜을 낸다.
    apply_method = "pending-reboot"
  }

  # IAM 인증 접속은 CloudTrail 에 남지 않아 이 로그로 감사한다.
  parameter {
    name         = "log_connections"
    value        = "1"
    apply_method = "immediate"
  }
}

# RDS 가 내보내는 이름(/aws/rds/instance/<identifier>/<log_type>)과 같아야 한다.
# 인스턴스보다 먼저 만들어야 RDS 가 보관 무기한 그룹을 따로 만들지 않는다.
resource "aws_cloudwatch_log_group" "rds_postgresql" {
  name              = "/aws/rds/instance/${local.rds_identifier}/postgresql"
  retention_in_days = 30
}

resource "aws_db_instance" "this" {
  identifier = local.rds_identifier

  engine                      = "postgres"
  engine_version              = "17"
  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false
  instance_class              = "db.t4g.medium"
  parameter_group_name        = aws_db_parameter_group.this.name
  ca_cert_identifier          = "rds-ca-rsa2048-g1"

  # 단일 AZ — Media 상자와 같은 AZ 에 둔다.
  multi_az               = false
  availability_zone      = local.media_az
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  port                   = 5432

  storage_type          = "gp3"
  allocated_storage     = 20
  max_allocated_storage = 50
  storage_encrypted     = true

  db_name                             = "pokeclip"
  username                            = "pokeclip_admin"
  manage_master_user_password         = true
  iam_database_authentication_enabled = true

  # 백업 KST 03:00–04:00 · 정비 KST 월 04:30–05:30(UTC 로 적는다).
  backup_retention_period = 7
  backup_window           = "18:00-19:00"
  maintenance_window      = "sun:19:30-sun:20:30"
  copy_tags_to_snapshot   = true

  # 삭제는 보호를 먼저 꺼야 한다. 최종 스냅숏은 남기지 않는다(결정 50).
  deletion_protection = true
  skip_final_snapshot = true

  enabled_cloudwatch_logs_exports = ["postgresql"]

  depends_on = [aws_cloudwatch_log_group.rds_postgresql]
}

output "rds_sg_id" {
  description = "RDS 전용 SG id — platform(RDS ← auth · clip) · media(Media ↔ RDS 5432)가 규칙을 붙인다."
  value       = aws_security_group.rds.id
}

output "rds_endpoint" {
  description = "RDS 호스트 이름(포트 없음 — 포트는 5432) — media 사이드카 · janitor · app_env 의 DB_HOST."
  value       = aws_db_instance.this.address
}

output "rds_db_name" {
  description = "DB 이름 — media · app_env 의 POSTGRES_DB."
  value       = aws_db_instance.this.db_name
}

output "rds_resource_id" {
  description = "RDS 리소스 id — media 인스턴스 역할 rds-db:connect 의 대상."
  value       = aws_db_instance.this.resource_id
}

output "rds_master_user_secret_arn" {
  description = "관리형 마스터 비밀 ARN — 운영 전용(SQL 실행기 · 런북). 소비 루트 계약이 아니다."
  value       = one(aws_db_instance.this.master_user_secret[*].secret_arn)
}
