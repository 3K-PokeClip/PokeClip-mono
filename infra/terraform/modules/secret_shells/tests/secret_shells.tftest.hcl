# 비밀 그릇이 입력 이름마다 하나씩, 이름 그대로 생기고 이름별 ARN 을 내는지
# mock provider 로 단언한다. 값(버전) · 리소스 정책 · 읽기 역할 입력이 없는
# 것은 guard R1 · R6 이 맡는다.

mock_provider "aws" {}

variables {
  names = ["example/signing", "example/cdn", "example/test-key"]
}

run "creates_one_shell_per_name" {
  command = plan

  assert {
    condition     = length(aws_secretsmanager_secret.this) == 3
    error_message = "비밀 그릇이 입력 이름 수(3)만큼 생기지 않는다."
  }

  assert {
    condition = toset([
      for secret in aws_secretsmanager_secret.this : secret.name
    ]) == toset(var.names)
    error_message = "비밀 이름이 입력 names 와 다르다(접두 · 변형 없이 그대로여야 한다)."
  }

  assert {
    condition     = toset(keys(aws_secretsmanager_secret.this)) == toset(var.names)
    error_message = "for_each 키가 비밀 이름이 아니다."
  }
}

# ARN 은 계산 속성이다. apply 로 돌려 mock 이 이름마다 다른 값을 만들게 한 뒤,
# 출력의 이름 → ARN 짝이 어긋나지 않았는지 본다(plan 의 override 는 모든
# 인스턴스에 같은 값을 넣어 짝 어긋남을 못 잡는다).
run "outputs_arn_per_name" {
  command = apply

  assert {
    condition     = toset(keys(output.secret_arns)) == toset(var.names)
    error_message = "출력 secret_arns 의 키가 입력 names 와 다르다."
  }

  assert {
    condition = alltrue([
      for name in var.names :
      output.secret_arns[name] == aws_secretsmanager_secret.this[name].arn
    ])
    error_message = "출력 secret_arns 의 이름 → ARN 짝이 비밀 그릇과 다르다."
  }
}
