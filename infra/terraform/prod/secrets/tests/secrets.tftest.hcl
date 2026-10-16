# prod 비밀 그릇 세 개의 이름(pokeclip/prod/<용도>)과 출력 넷(설계 3-2 —
# 소비 루트와의 계약)을 단언한다. PEM 은 시험용 가짜 값이다(실제 키 아님).

mock_provider "aws" {}

variables {
  public_key_pem = "-----BEGIN PUBLIC KEY-----\nexample\n-----END PUBLIC KEY-----\n"
}

run "creates_three_prod_secret_shells" {
  command = plan

  assert {
    condition = toset(keys(module.secret_shells.secret_arns)) == toset([
      "pokeclip/prod/signing-key",
      "pokeclip/prod/cdn-secret",
      "pokeclip/prod/test-stream-key",
    ])
    error_message = "비밀 이름 집합이 pokeclip/prod/{signing-key,cdn-secret,test-stream-key} 와 다르다."
  }
}

# ARN · id 는 계산 속성이다. apply 로 돌려 mock 이 그릇마다 다른 ARN 을 만들게
# 한 뒤, 출력마다 맞는 그릇을 가리키는지 본다.
run "outputs_match_consumer_contract" {
  command = apply

  assert {
    condition     = output.signing_key_secret_arn == module.secret_shells.secret_arns["pokeclip/prod/signing-key"]
    error_message = "출력 signing_key_secret_arn 이 서명 키 그릇 ARN 이 아니다."
  }

  assert {
    condition     = output.cdn_secret_arn == module.secret_shells.secret_arns["pokeclip/prod/cdn-secret"]
    error_message = "출력 cdn_secret_arn 이 CDN 비밀 그릇 ARN 이 아니다."
  }

  assert {
    condition     = output.test_stream_key_secret_arn == module.secret_shells.secret_arns["pokeclip/prod/test-stream-key"]
    error_message = "출력 test_stream_key_secret_arn 이 시험 키 그릇 ARN 이 아니다."
  }

  assert {
    condition     = output.public_key_id == module.cloudfront_public_key.public_key_id
    error_message = "출력 public_key_id 가 공개키 모듈 출력과 다르다."
  }
}
