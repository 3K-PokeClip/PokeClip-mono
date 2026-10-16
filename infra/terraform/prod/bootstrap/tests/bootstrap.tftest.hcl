# prod 상태 버킷이 state_bucket 모듈로, 고정 이름(기본값)으로 만들어지고 이름 ·
# ARN 을 내는지 본다. 이름은 변수로 덮지 않는다 — 기본값 자체를 단언한다.

mock_provider "aws" {}

run "creates_prod_state_bucket_with_fixed_name" {
  command = plan

  # ARN 은 계산 속성이라 plan 단계에서 고정한다. 이름과 무관한 표지를 써서,
  # 출력이 모듈의 ARN 을 그대로 넘기는지(이름으로 조립하지 않는지) 본다.
  override_resource {
    target          = module.state_bucket.aws_s3_bucket.this
    override_during = plan
    values = {
      arn = "arn:aws:s3:::override-sentinel"
    }
  }

  assert {
    condition     = var.state_bucket_name == "pokeclip-tfstate-prod-apne2"
    error_message = "state_bucket_name 기본값이 pokeclip-tfstate-prod-apne2 가 아니다(결정 44 ③)."
  }

  assert {
    condition     = output.state_bucket_name == "pokeclip-tfstate-prod-apne2"
    error_message = "출력 state_bucket_name 이 고정 이름과 다르다."
  }

  assert {
    condition     = output.state_bucket_arn == "arn:aws:s3:::override-sentinel"
    error_message = "출력 state_bucket_arn 이 모듈의 버킷 ARN 을 그대로 넘기지 않는다."
  }
}
