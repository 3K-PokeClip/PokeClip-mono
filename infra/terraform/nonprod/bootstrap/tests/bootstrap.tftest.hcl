# nonprod 상태 버킷이 state_bucket 모듈로 만들어지고 이름 · ARN 을 내는지 본다.

mock_provider "aws" {}

variables {
  state_bucket_name = "example-nonprod-tfstate"
}

run "creates_protected_state_bucket" {
  command = plan

  override_resource {
    target          = module.state_bucket.aws_s3_bucket.this
    override_during = plan
    values = {
      arn = "arn:aws:s3:::example-nonprod-tfstate"
    }
  }

  assert {
    condition     = module.state_bucket.bucket_name == var.state_bucket_name
    error_message = "상태 버킷 이름이 state_bucket_name 과 다르다."
  }

  assert {
    condition     = output.state_bucket_name == "example-nonprod-tfstate"
    error_message = "출력 state_bucket_name 이 버킷 이름과 다르다."
  }

  assert {
    condition     = output.state_bucket_arn == "arn:aws:s3:::example-nonprod-tfstate"
    error_message = "출력 state_bucket_arn 이 버킷 ARN 과 다르다."
  }
}
