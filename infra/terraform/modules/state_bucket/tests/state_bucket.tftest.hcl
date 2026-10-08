# 상태 버킷의 보호 설정을 mock provider 로 단언한다.
# prevent_destroy 는 tftest 로 볼 수 없어 guard R4 가 맡는다.

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
