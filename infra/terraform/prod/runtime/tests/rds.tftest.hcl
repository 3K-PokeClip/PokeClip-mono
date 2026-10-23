# POK-304 prod RDS — 인스턴스 · 파라미터 그룹 · 서브넷 그룹 · 로그 그룹 · SG 의
# 속성(설계 D2 · 계획 c2 · H4 · P4)과 출력 다섯(CONTRACT-runtime.md 「출력과
# 소유 경계」)을 단언한다. network 출력 · 마스터 비밀 식별자는 가짜 값이다.
# override_data 가 원격 상태를 대신하므로 S3 를 읽지 않는다(AWS 호출 0).

mock_provider "aws" {
  source = "./tests/mocks"
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

# H4 — 메이저만 고정한다. provider 는 설정한 판이 실제 판의 「점 경계 접두」
# 이면 차이로 보지 않으므로, AWS 가 부판을 올려도 plan 이 조용하다.
run "pins_postgres_major_version_only" {
  command = plan

  assert {
    condition     = aws_db_instance.this.engine == "postgres"
    error_message = "engine 이 postgres 가 아니다."
  }

  assert {
    condition     = aws_db_instance.this.engine_version == "17"
    error_message = "engine_version 이 메이저만 적은 \"17\" 이 아니다(H4)."
  }

  assert {
    condition     = !strcontains(aws_db_instance.this.engine_version, ".")
    error_message = "engine_version 에 점이 있다 — 부판까지 고정하면 AWS 강제 부판 업그레이드 뒤 apply 가 되돌리려 한다(H4)."
  }

  assert {
    condition     = aws_db_instance.this.auto_minor_version_upgrade == true
    error_message = "auto_minor_version_upgrade 가 true 가 아니다(H4)."
  }

  assert {
    condition     = aws_db_instance.this.allow_major_version_upgrade == false
    error_message = "allow_major_version_upgrade 가 false 가 아니다(H4)."
  }
}

run "places_single_az_instance_in_media_az" {
  command = plan

  assert {
    condition     = aws_db_instance.this.identifier == "pokeclip-prod-db"
    error_message = "identifier 가 pokeclip-prod-db 가 아니다(P4)."
  }

  assert {
    condition     = aws_db_instance.this.instance_class == "db.t4g.medium"
    error_message = "instance_class 가 db.t4g.medium 이 아니다(결정 146)."
  }

  assert {
    condition     = aws_db_instance.this.multi_az == false
    error_message = "multi_az 가 false 가 아니다(단일 AZ)."
  }

  assert {
    condition     = aws_db_instance.this.availability_zone == local.media_az
    error_message = "availability_zone 이 network 의 media_az 가 아니다."
  }

  assert {
    condition     = aws_db_subnet_group.this.name == "pokeclip-prod-db"
    error_message = "서브넷 그룹 이름이 pokeclip-prod-db 가 아니다."
  }

  assert {
    condition     = toset(aws_db_subnet_group.this.subnet_ids) == toset(local.private_subnet_ids)
    error_message = "서브넷 그룹이 network 의 private_subnet_ids 를 쓰지 않는다."
  }

  assert {
    condition     = aws_db_instance.this.db_subnet_group_name == aws_db_subnet_group.this.name
    error_message = "인스턴스가 이 루트의 서브넷 그룹을 쓰지 않는다."
  }

  assert {
    condition     = aws_db_instance.this.publicly_accessible == false
    error_message = "publicly_accessible 이 false 가 아니다."
  }

  assert {
    condition     = aws_db_instance.this.port == 5432
    error_message = "port 가 5432 가 아니다."
  }
}

run "sizes_encrypted_gp3_storage" {
  command = plan

  assert {
    condition     = aws_db_instance.this.storage_type == "gp3"
    error_message = "storage_type 이 gp3 가 아니다."
  }

  assert {
    condition     = aws_db_instance.this.allocated_storage == 20
    error_message = "allocated_storage 가 20 이 아니다."
  }

  assert {
    condition     = aws_db_instance.this.max_allocated_storage == 50
    error_message = "max_allocated_storage(자동 확장 상한)가 50 이 아니다."
  }

  assert {
    condition     = aws_db_instance.this.storage_encrypted == true
    error_message = "storage_encrypted 가 true 가 아니다."
  }
}

# 백업 창 KST 03:00–04:00(UTC 18:00–19:00) · 정비 창 KST 월 04:30–05:30
# (UTC 일 19:30–20:30) — 둘은 겹치지 않는다.
run "keeps_backups_and_protects_from_deletion" {
  command = plan

  assert {
    condition     = aws_db_instance.this.backup_retention_period == 7
    error_message = "backup_retention_period 가 7 이 아니다."
  }

  assert {
    condition     = aws_db_instance.this.backup_window == "18:00-19:00"
    error_message = "backup_window 가 UTC 18:00-19:00(KST 03:00-04:00)이 아니다."
  }

  assert {
    condition     = aws_db_instance.this.maintenance_window == "sun:19:30-sun:20:30"
    error_message = "maintenance_window 가 UTC sun:19:30-sun:20:30(KST 월 04:30-05:30)이 아니다."
  }

  assert {
    condition     = aws_db_instance.this.copy_tags_to_snapshot == true
    error_message = "copy_tags_to_snapshot 이 true 가 아니다."
  }

  assert {
    condition     = aws_db_instance.this.deletion_protection == true
    error_message = "deletion_protection 이 true 가 아니다."
  }

  assert {
    condition     = aws_db_instance.this.skip_final_snapshot == true
    error_message = "skip_final_snapshot 이 true 가 아니다(결정 50)."
  }
}

# 마스터 비밀번호는 RDS 관리형 비밀에 둔다(D1) — 상태에 비밀 값이 없다.
run "manages_master_password_without_state_secret" {
  command = plan

  assert {
    condition     = aws_db_instance.this.manage_master_user_password == true
    error_message = "manage_master_user_password 가 true 가 아니다(D1)."
  }

  assert {
    condition     = aws_db_instance.this.password == null
    error_message = "password 가 설정돼 있다 — 비밀번호가 상태에 남는다(D1)."
  }

  assert {
    condition     = aws_db_instance.this.username == "pokeclip_admin"
    error_message = "username 이 pokeclip_admin 이 아니다."
  }

  assert {
    condition     = aws_db_instance.this.db_name == "pokeclip"
    error_message = "db_name 이 pokeclip 이 아니다."
  }

  assert {
    condition     = aws_db_instance.this.iam_database_authentication_enabled == true
    error_message = "iam_database_authentication_enabled 가 true 가 아니다(결정 38)."
  }

  assert {
    condition     = aws_db_instance.this.ca_cert_identifier == "rds-ca-rsa2048-g1"
    error_message = "ca_cert_identifier 가 rds-ca-rsa2048-g1 이 아니다."
  }
}

run "forces_tls_and_logs_connections" {
  command = plan

  assert {
    condition = (
      aws_db_parameter_group.this.name == "pokeclip-prod-pg17" &&
      aws_db_parameter_group.this.family == "postgres17"
    )
    error_message = "파라미터 그룹이 pokeclip-prod-pg17 · postgres17 이 아니다."
  }

  assert {
    condition = {
      for p in aws_db_parameter_group.this.parameter : p.name => p.value
      } == {
      "rds.force_ssl"   = "1"
      "log_connections" = "1"
    }
    error_message = "파라미터가 정확히 rds.force_ssl=1 · log_connections=1 이 아니다."
  }

  assert {
    condition     = aws_db_instance.this.parameter_group_name == aws_db_parameter_group.this.name
    error_message = "인스턴스가 이 루트의 파라미터 그룹을 쓰지 않는다."
  }
}

# 로그 그룹 이름은 RDS 가 내보내는 이름과 같아야 한다 — 다르면 RDS 가 보관
# 무기한 그룹을 따로 만든다(K6 · P4).
run "exports_postgresql_log_to_30_day_group" {
  command = plan

  assert {
    condition     = toset(aws_db_instance.this.enabled_cloudwatch_logs_exports) == toset(["postgresql"])
    error_message = "enabled_cloudwatch_logs_exports 가 [\"postgresql\"] 이 아니다."
  }

  assert {
    condition     = aws_cloudwatch_log_group.rds_postgresql.name == "/aws/rds/instance/${aws_db_instance.this.identifier}/postgresql"
    error_message = "로그 그룹 이름이 /aws/rds/instance/<identifier>/postgresql 이 아니다(P4)."
  }

  assert {
    condition     = aws_cloudwatch_log_group.rds_postgresql.retention_in_days == 30
    error_message = "로그 그룹 보관이 30일이 아니다(K6)."
  }
}

# 규칙은 소비 루트가 만든다 — 이 SG 는 규칙 0(인라인은 guard R3 가 막는다).
run "creates_dedicated_rds_security_group" {
  command = plan

  assert {
    condition     = aws_security_group.rds.name == "pokeclip-prod-rds"
    error_message = "RDS SG 이름이 pokeclip-prod-rds 가 아니다."
  }

  assert {
    condition     = aws_security_group.rds.vpc_id == local.vpc_id
    error_message = "RDS SG 가 network 의 VPC 에 있지 않다."
  }
}

# id · 주소는 계산 속성이다. apply 로 돌려 mock 이 값을 만들게 한 뒤 출력이
# 맞는 속성을 가리키는지 본다. 마스터 비밀은 mock 이 블록을 비워 두므로
# 가짜 식별자로 채운다.
run "outputs_match_consumer_contract" {
  command = apply

  override_resource {
    target = aws_db_instance.this
    values = {
      master_user_secret = [
        {
          kms_key_id    = "example-kms-key"
          secret_arn    = "example-master-user-secret"
          secret_status = "active"
        },
      ]
    }
  }

  assert {
    condition     = output.rds_sg_id == aws_security_group.rds.id
    error_message = "출력 rds_sg_id 가 RDS SG id 가 아니다."
  }

  assert {
    condition     = toset(aws_db_instance.this.vpc_security_group_ids) == toset([aws_security_group.rds.id])
    error_message = "인스턴스 SG 가 RDS SG 하나가 아니다(VPC 기본 SG 금지)."
  }

  assert {
    condition = (
      output.rds_endpoint == aws_db_instance.this.address &&
      output.rds_endpoint != aws_db_instance.this.endpoint
    )
    error_message = "출력 rds_endpoint 가 address(호스트만)가 아니다 — endpoint 는 호스트:포트다."
  }

  assert {
    condition     = output.rds_db_name == "pokeclip"
    error_message = "출력 rds_db_name 이 pokeclip 이 아니다."
  }

  assert {
    condition     = output.rds_resource_id == aws_db_instance.this.resource_id
    error_message = "출력 rds_resource_id 가 인스턴스 resource_id 가 아니다."
  }

  assert {
    condition     = output.rds_master_user_secret_arn == "example-master-user-secret"
    error_message = "출력 rds_master_user_secret_arn 이 관리형 마스터 비밀 ARN 이 아니다."
  }
}
