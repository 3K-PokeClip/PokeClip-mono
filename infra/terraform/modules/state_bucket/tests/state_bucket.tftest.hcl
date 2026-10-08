# 상태 버킷의 보호 설정을 mock provider 로 단언한다.
# prevent_destroy 는 tftest 로 볼 수 없어 guard R4 가 맡는다.
# 정책은 jsonencode 로 만든 기대값과 jsondecode 해서 비교한다.

mock_provider "aws" {}

variables {
  name = "example-tfstate"
}

run "bucket_is_protected" {
  command = plan

  assert {
    condition     = aws_s3_bucket.this.bucket == var.name
    error_message = "버킷 이름이 입력 name 과 다르다."
  }

  assert {
    condition     = aws_s3_bucket_versioning.this.versioning_configuration[0].status == "Enabled"
    error_message = "버전 관리가 Enabled 가 아니다."
  }

  assert {
    condition = one([
      for rule in aws_s3_bucket_server_side_encryption_configuration.this.rule :
      rule.apply_server_side_encryption_by_default[0].sse_algorithm
    ]) == "AES256"
    error_message = "기본 암호화(SSE-S3)가 없다."
  }

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.this.block_public_acls,
      aws_s3_bucket_public_access_block.this.block_public_policy,
      aws_s3_bucket_public_access_block.this.ignore_public_acls,
      aws_s3_bucket_public_access_block.this.restrict_public_buckets,
    ])
    error_message = "퍼블릭 차단 네 항목이 모두 true 가 아니다."
  }

  assert {
    condition = one([
      for rule in aws_s3_bucket_ownership_controls.this.rule :
      rule.object_ownership
    ]) == "BucketOwnerEnforced"
    error_message = "객체 소유권이 BucketOwnerEnforced(ACL 끔)가 아니다."
  }

  assert {
    condition = one([
      for rule in aws_s3_bucket_lifecycle_configuration.this.rule : rule.status
    ]) == "Enabled"
    error_message = "수명주기 규칙이 하나(Enabled)가 아니다."
  }

  assert {
    condition = one(flatten([
      for rule in aws_s3_bucket_lifecycle_configuration.this.rule : [
        for expiration in rule.noncurrent_version_expiration :
        expiration.noncurrent_days
      ]
    ])) == 90
    error_message = "이전 버전 만료가 90일이 아니다."
  }

  assert {
    condition = one(flatten([
      for rule in aws_s3_bucket_lifecycle_configuration.this.rule : [
        for expiration in rule.noncurrent_version_expiration :
        expiration.newer_noncurrent_versions
      ]
    ])) == 5
    error_message = "90일이 지나도 최근 이전 버전 5개는 남겨야 한다."
  }

  assert {
    condition = one(flatten([
      for rule in aws_s3_bucket_lifecycle_configuration.this.rule : [
        for abort in rule.abort_incomplete_multipart_upload :
        abort.days_after_initiation
      ]
    ])) == 7
    error_message = "불완전 멀티파트 업로드 중단이 7일이 아니다."
  }
}

run "policy_denies_insecure_transport" {
  command = plan

  # 정책의 Resource 는 버킷 ARN(계산 속성)이라 plan 단계에서 고정한다.
  override_resource {
    target          = aws_s3_bucket.this
    override_during = plan
    values = {
      arn = "arn:aws:s3:::example-tfstate"
    }
  }

  assert {
    condition = jsondecode(aws_s3_bucket_policy.this.policy) == jsondecode(jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Sid       = "DenyInsecureTransport"
          Effect    = "Deny"
          Principal = "*"
          Action    = "s3:*"
          Resource = [
            "arn:aws:s3:::example-tfstate",
            "arn:aws:s3:::example-tfstate/*",
          ]
          Condition = { Bool = { "aws:SecureTransport" = "false" } }
        },
        {
          Sid       = "DenyOutdatedTls"
          Effect    = "Deny"
          Principal = "*"
          Action    = "s3:*"
          Resource = [
            "arn:aws:s3:::example-tfstate",
            "arn:aws:s3:::example-tfstate/*",
          ]
          Condition = { NumericLessThan = { "s3:TlsVersion" = 1.2 } }
        },
      ]
    }))
    error_message = "버킷 정책이 HTTP · TLS 1.2 미만 거부 두 문장과 다르다."
  }
}

run "outputs_name_the_bucket" {
  command = plan

  # bucket_arn 은 계산 속성이라 plan 단계에서 고정한다.
  override_resource {
    target          = aws_s3_bucket.this
    override_during = plan
    values = {
      arn = "arn:aws:s3:::example-tfstate"
    }
  }

  assert {
    condition     = output.bucket_name == "example-tfstate"
    error_message = "출력 bucket_name 이 버킷 이름과 다르다."
  }

  assert {
    condition     = output.bucket_arn == "arn:aws:s3:::example-tfstate"
    error_message = "출력 bucket_arn 이 버킷 ARN 과 다르다."
  }
}
