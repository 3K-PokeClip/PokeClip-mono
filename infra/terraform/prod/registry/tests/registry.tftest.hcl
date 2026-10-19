# prod ECR 저장소(설계 3-1 의 ⑦ — ECR 만)를 mock provider 로 단언한다. 저장소
# 키 집합 · 이름 규약(pokeclip-prod/<키>) · 태그 불변과 푸시 때 스캔(변수 기본
# 켬) · 출력 repo_urls. IAM 역할 · GitHub OIDC 공급자가 없다는 것은 tftest 로
# 볼 수 없어 리뷰가 확인한다.

mock_provider "aws" {}

run "declares_seven_prod_repositories" {
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
    ])
    error_message = "저장소 키 집합이 next · auth · clip · media-{nginx,mediamtx,sidecar,ingest-auth} 와 다르다."
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
