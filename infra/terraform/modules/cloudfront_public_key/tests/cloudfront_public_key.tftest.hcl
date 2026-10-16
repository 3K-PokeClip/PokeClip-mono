# CloudFront 공개키가 입력 이름 · PEM 으로 만들어지고 id 를 내는지 mock
# provider 로 단언한다. PEM 은 시험용 가짜 값이다(실제 키 아님).
# encoded_key 는 끝 줄바꿈을 하나로 맞춘다 — PEM 파일 끝 줄바꿈의 유무로 값이
# 달라지면 공개키를 교체하는 계획이 나온다.

mock_provider "aws" {}

variables {
  name           = "example-signing-key"
  public_key_pem = "-----BEGIN PUBLIC KEY-----\nexample\n-----END PUBLIC KEY-----"
}

run "creates_named_public_key" {
  command = plan

  assert {
    condition     = aws_cloudfront_public_key.this.name == "example-signing-key"
    error_message = "공개키 이름이 입력 name 과 다르다."
  }

  assert {
    condition     = aws_cloudfront_public_key.this.encoded_key == "-----BEGIN PUBLIC KEY-----\nexample\n-----END PUBLIC KEY-----\n"
    error_message = "끝 줄바꿈이 없는 PEM 에 줄바꿈 하나를 붙이지 않았다."
  }
}

run "normalizes_trailing_newlines" {
  command = plan

  variables {
    public_key_pem = "-----BEGIN PUBLIC KEY-----\nexample\n-----END PUBLIC KEY-----\n\n"
  }

  assert {
    condition     = aws_cloudfront_public_key.this.encoded_key == "-----BEGIN PUBLIC KEY-----\nexample\n-----END PUBLIC KEY-----\n"
    error_message = "끝 줄바꿈 여러 개를 하나로 맞추지 않았다."
  }
}

run "keeps_single_trailing_newline" {
  command = plan

  variables {
    public_key_pem = "-----BEGIN PUBLIC KEY-----\nexample\n-----END PUBLIC KEY-----\n"
  }

  assert {
    condition     = aws_cloudfront_public_key.this.encoded_key == "-----BEGIN PUBLIC KEY-----\nexample\n-----END PUBLIC KEY-----\n"
    error_message = "끝 줄바꿈 하나인 PEM 을 그대로 두지 않았다."
  }
}

run "outputs_public_key_id" {
  command = plan

  # id 는 계산 속성이라 plan 단계에서 고정한다.
  override_resource {
    target          = aws_cloudfront_public_key.this
    override_during = plan
    values = {
      id = "EXAMPLEPUBLICKEYID"
    }
  }

  assert {
    condition     = output.public_key_id == "EXAMPLEPUBLICKEYID"
    error_message = "출력 public_key_id 가 공개키 id 와 다르다."
  }
}

# 공개키 PEM 이 아닌 입력은 plan 전에 거부한다 — 개인키를 잘못 넘기면 plan ·
# tfplan 에 평문으로 남는다. 키 꼴 머리 줄은 비밀 스캐너가 실제 키로 오인하지
# 않게 ${""} 로 나눠 쓴다(본문은 가짜).

run "rejects_private_key_pem" {
  command = plan

  variables {
    public_key_pem = "-----BEGIN ${""}PRIVATE KEY-----\nexample\n-----END ${""}PRIVATE KEY-----\n"
  }

  expect_failures = [var.public_key_pem]
}

run "rejects_pem_without_public_key_header" {
  command = plan

  variables {
    public_key_pem = "example\n"
  }

  expect_failures = [var.public_key_pem]
}

# 공개키 파일 뒤에 개인키 파일을 이어 붙인 꼴 — 머리 줄은 맞아도 거부한다.
run "rejects_public_key_followed_by_private_key" {
  command = plan

  variables {
    public_key_pem = "-----BEGIN PUBLIC KEY-----\nexample\n-----END PUBLIC KEY-----\n-----BEGIN ${""}PRIVATE KEY-----\nexample\n-----END ${""}PRIVATE KEY-----\n"
  }

  expect_failures = [var.public_key_pem]
}
