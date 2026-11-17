# prod ECR 저장소(설계 3-1 의 ⑦)와 CI 역할(prod CI B12)을 mock provider 로
# 단언한다. 저장소 키 집합 · 이름 규약(pokeclip-prod/<키>) · 태그 불변과 푸시
# 때 스캔(변수 기본 켬) · 출력 repo_urls, 그리고 CI 역할 둘의 신뢰(GitHub
# OIDC 공급자 data source · StringEquals aud · sub 두 키) · 이름 · 경로 · 푸시
# 정책(동작 집합 · 저장소 ARN 범위 · 삭제 0) · 출력 셋. tftest 는 선언의
# 부재를 볼 수 없다 — apex 업로드 역할에 붙는 정책 자원이 없다는 것은 guard
# R7 이, OIDC 공급자 자원이 없다는 것은 리뷰가 확인한다.

mock_provider "aws" {}

run "declares_thirteen_prod_repositories" {
  command = plan

  assert {
    condition = toset(keys(aws_ecr_repository.this)) == toset([
      "next",
      "auth",
      "clip",
      "media-nginx",
      "media-mediamtx",
      "media-sidecar",
      "media-ingest-auth",
      "media-loopback-sender",
      "media-host-bundle",
      "render",
      "upload",
      "chat-collector",
      "chat-detector",
    ])
    error_message = "저장소 키 집합이 next · auth · clip · media-{nginx,mediamtx,sidecar,ingest-auth,loopback-sender,host-bundle} · render · upload · chat-{collector,detector} 와 다르다."
  }

  assert {
    condition = alltrue([
      for key, repository in aws_ecr_repository.this : repository.name == "pokeclip-prod/${key}"
    ])
    error_message = "저장소 이름이 pokeclip-prod/<키> 가 아니다."
  }
}

run "uses_immutable_tags_and_scan_on_push_by_default" {
  command = plan

  assert {
    condition = alltrue([
      for repository in aws_ecr_repository.this : repository.image_tag_mutability == "IMMUTABLE"
    ])
    error_message = "기본값에서 저장소 태그가 불변(IMMUTABLE)이 아니다."
  }

  assert {
    condition = alltrue([
      for repository in aws_ecr_repository.this : repository.image_scanning_configuration[0].scan_on_push
    ])
    error_message = "기본값에서 푸시 때 스캔이 켜져 있지 않다."
  }

  # 이미지가 남은 저장소를 지우는 plan 이 이미지까지 지우지 않게 한다.
  assert {
    condition = alltrue([
      for repository in aws_ecr_repository.this : repository.force_delete != true
    ])
    error_message = "저장소에 force_delete 가 켜져 있다."
  }
}

run "variables_turn_off_immutability_and_scan" {
  command = plan

  variables {
    image_tag_immutable = false
    scan_on_push        = false
  }

  assert {
    condition = alltrue([
      for repository in aws_ecr_repository.this :
      repository.image_tag_mutability == "MUTABLE" && !repository.image_scanning_configuration[0].scan_on_push
    ])
    error_message = "변수를 꺼도 태그 가변 · 스캔 끔으로 바뀌지 않는다."
  }
}

# repository_url 은 계산 속성이다. apply 로 돌려 mock 이 저장소마다 다른 값을
# 만들게 한 뒤 키 → URL 짝을 본다.
run "outputs_repo_url_per_key" {
  command = apply

  assert {
    condition     = toset(keys(output.repo_urls)) == toset(keys(aws_ecr_repository.this))
    error_message = "출력 repo_urls 의 키가 저장소 키와 다르다."
  }

  assert {
    condition = alltrue([
      for key, repository in aws_ecr_repository.this : output.repo_urls[key] == repository.repository_url
    ])
    error_message = "출력 repo_urls 의 키 → URL 짝이 저장소와 다르다."
  }
}

# --- CI 역할(GitHub Actions OIDC — prod CI B12) ---

# 계정에 이미 있는 GitHub OIDC 공급자다. ARN 을 고정해, 신뢰 정책의
# Federated 가 이 data source 가 아닌 값을 가리키면 실패하게 한다.
override_data {
  target = data.aws_iam_openid_connect_provider.github
  values = {
    arn = "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com"
  }
}

# 공급자는 만들지도 import 하지도 않고 URL 로 찾는다.
run "finds_existing_github_oidc_provider_by_url" {
  command = plan

  assert {
    condition     = data.aws_iam_openid_connect_provider.github.url == "https://token.actions.githubusercontent.com"
    error_message = "GitHub OIDC 공급자를 https://token.actions.githubusercontent.com 으로 찾지 않는다."
  }
}

# 두 역할의 신뢰 정책은 문 하나 — 그 공급자의 웹 ID 역할 받기를
# StringEquals 두 키(aud · sub)로만 허용한다. sub 는 고정 subject 꼴의
# prod-deploy environment 하나다(main + 필수 승인자는 environment 가 건다).
run "ci_roles_trust_only_prod_deploy_environment" {
  command = plan

  assert {
    condition = alltrue([
      for role in [aws_iam_role.ci_push, aws_iam_role.apex_upload] :
      length(jsondecode(role.assume_role_policy).Statement) == 1
    ])
    error_message = "CI 역할의 신뢰 정책 문이 하나가 아니다."
  }

  assert {
    condition = alltrue([
      for role in [aws_iam_role.ci_push, aws_iam_role.apex_upload] :
      jsondecode(role.assume_role_policy).Statement[0].Effect == "Allow" &&
      jsondecode(role.assume_role_policy).Statement[0].Action == "sts:AssumeRoleWithWebIdentity" &&
      jsondecode(role.assume_role_policy).Statement[0].Principal == {
        Federated = data.aws_iam_openid_connect_provider.github.arn
      } &&
      data.aws_iam_openid_connect_provider.github.arn == "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com"
    ])
    error_message = "CI 역할의 신뢰가 GitHub OIDC 공급자(data source)의 sts:AssumeRoleWithWebIdentity 허용 하나가 아니다."
  }

  # 조건 연산자는 StringEquals 하나다 — StringLike 가 끼면 와일드카드 sub 가
  # 다른 environment · 브랜치를 받는다.
  assert {
    condition = alltrue([
      for role in [aws_iam_role.ci_push, aws_iam_role.apex_upload] :
      keys(jsondecode(role.assume_role_policy).Statement[0].Condition) == ["StringEquals"]
    ])
    error_message = "CI 역할의 신뢰 조건 연산자가 StringEquals 하나가 아니다(StringLike 등 금지)."
  }

  assert {
    condition = alltrue([
      for role in [aws_iam_role.ci_push, aws_iam_role.apex_upload] :
      jsondecode(role.assume_role_policy).Statement[0].Condition.StringEquals == {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = "repo:3K-PokeClip@304758179/PokeClip-mono@1311593360:environment:prod-deploy"
      }
    ])
    error_message = "CI 역할의 StringEquals 가 aud = sts.amazonaws.com · sub = prod-deploy environment 고정 subject 두 키가 아니다."
  }

  assert {
    condition = alltrue(flatten([
      for role in [aws_iam_role.ci_push, aws_iam_role.apex_upload] : [
        for value in values(jsondecode(role.assume_role_policy).Statement[0].Condition.StringEquals) :
        !strcontains(value, "*") && !strcontains(value, "?")
      ]
    ]))
    error_message = "CI 역할의 신뢰 조건 값에 와일드카드(* · ?)가 있다."
  }
}

run "ci_roles_live_under_prod_path" {
  command = plan

  assert {
    condition = (
      aws_iam_role.ci_push.name == "pokeclip-prod-ci-ecr-push" &&
      aws_iam_role.apex_upload.name == "pokeclip-prod-ci-apex-upload" &&
      aws_iam_role.ci_push.path == "/pokeclip/prod/" &&
      aws_iam_role.apex_upload.path == "/pokeclip/prod/"
    )
    error_message = "CI 역할 이름이 pokeclip-prod-ci-ecr-push · pokeclip-prod-ci-apex-upload 가 아니거나 경로가 /pokeclip/prod/ 가 아니다."
  }
}

# 저장소 ARN 은 계산 속성이다. apply 로 돌려 mock 이 저장소마다 값을 만들게
# 한 뒤 정책의 Resource 와 견준다.
run "ci_push_policy_pushes_only_to_prod_repositories" {
  command = apply

  assert {
    condition     = aws_iam_role_policy.ci_push.role == aws_iam_role.ci_push.name
    error_message = "푸시 정책이 CI 푸시 역할에 붙어 있지 않다."
  }

  assert {
    condition = (
      length(jsondecode(aws_iam_role_policy.ci_push.policy).Statement) == 2 &&
      [
        for statement in jsondecode(aws_iam_role_policy.ci_push.policy).Statement :
        [statement.Effect, statement.Action, statement.Resource]
      ][0] == ["Allow", "ecr:GetAuthorizationToken", "*"]
    )
    error_message = "푸시 정책이 GetAuthorizationToken(*) · 저장소 푸시 두 문이 아니다."
  }

  # BatchGetImage 는 AWS 푸시 예시 정책(ECR 사용 설명서 image-push-iam)대로
  # 넣는다 — 조사 ①.
  assert {
    condition = toset(jsondecode(aws_iam_role_policy.ci_push.policy).Statement[1].Action) == toset([
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ])
    error_message = "저장소 푸시 문의 동작 집합이 푸시 최소 일곱(BatchGetImage · DescribeImages 포함)과 다르다."
  }

  assert {
    condition = (
      jsondecode(aws_iam_role_policy.ci_push.policy).Statement[1].Effect == "Allow" &&
      toset(jsondecode(aws_iam_role_policy.ci_push.policy).Statement[1].Resource) == toset([
        for repository in aws_ecr_repository.this : repository.arn
      ]) &&
      length(jsondecode(aws_iam_role_policy.ci_push.policy).Statement[1].Resource) == length(aws_ecr_repository.this)
    )
    error_message = "저장소 푸시 문의 Resource 가 이 루트의 저장소 ARN 집합이 아니다."
  }

  # 삭제 · 와일드카드 동작 0 — 이미지 · 저장소를 지우는 권한이 끼지 않게 한다.
  assert {
    condition = alltrue(flatten([
      for statement in jsondecode(aws_iam_role_policy.ci_push.policy).Statement : [
        for action in flatten([statement.Action]) :
        !can(regex("(?i)delete|\\*", action))
      ]
    ]))
    error_message = "푸시 정책에 삭제 계열 · 와일드카드 동작이 있다."
  }
}

# apex 업로드 역할의 권한은 prod/edge 가 붙인다(guard R7 이 registry 안의
# 부착 선언을 막는다). 여기서는 역할 본문의 정책 인자가 비었는지만 본다.
run "apex_upload_role_carries_no_policy_in_registry" {
  command = apply

  assert {
    condition = (
      length(aws_iam_role.apex_upload.inline_policy) == 0 &&
      length(aws_iam_role.apex_upload.managed_policy_arns) == 0
    )
    error_message = "registry 의 apex 업로드 역할에 인라인 · 관리형 정책이 붙었다(권한은 prod/edge)."
  }
}

# 역할 ARN 은 계산 속성이라 apply 로 돌린다.
run "outputs_ci_role_identities" {
  command = apply

  assert {
    condition = (
      output.ci_push_role_arn == aws_iam_role.ci_push.arn &&
      output.apex_upload_role_arn == aws_iam_role.apex_upload.arn &&
      output.apex_upload_role_name == aws_iam_role.apex_upload.name &&
      output.apex_upload_role_name == "pokeclip-prod-ci-apex-upload"
    )
    error_message = "출력 ci_push_role_arn · apex_upload_role_arn · apex_upload_role_name 이 두 역할의 값이 아니다."
  }
}
