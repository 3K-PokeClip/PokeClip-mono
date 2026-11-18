# prod CI 역할(B12) — GitHub Actions 가 OIDC 로 받는 역할 둘이다. 신원 ·
# 신뢰는 이 루트가, apex 업로드 권한은 prod/edge 가 갖는다(guard R7 이
# 이 루트의 apex 역할 정책 부착을 막는다). 되돌릴 때는 edge 정책을 먼저
# 지운다 — provider 는 refresh 때 역할의 인라인 정책을 상태에 담고 역할을
# 지울 때 그 정책까지 지우므로, registry 를 먼저 지우면 edge 정책이 edge
# 상태 밖에서 사라져 edge 상태가 어긋난다.
#
# 두 역할은 같은 신뢰를 쓴다: prod-deploy environment 의 잡만 받는다. main
# 브랜치 · 필수 승인자는 environment 보호 규칙이 건다.

# 계정에 이미 있는 공급자다(dev CI 도 쓴다). 만들지도 import 하지도 않고
# URL 로 찾는다 — ARN 을 적으면 계정 ID 가 코드에 들어간다.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

locals {
  # 고정 subject 꼴(소유자 · 저장소 이름 뒤에 숫자 id) — 저장소 이름을 바꾸거나
  # 같은 이름으로 다시 만들어도 다른 저장소의 토큰이 맞지 않는다. id 는
  # GitHub API(repos/3K-PokeClip/PokeClip-mono 의 owner.id · id)와 같다.
  ci_subject = "repo:3K-PokeClip@304758179/PokeClip-mono@1311593360:environment:prod-deploy"

  ci_assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Federated = data.aws_iam_openid_connect_provider.github.arn }
        Action    = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
            "token.actions.githubusercontent.com:sub" = local.ci_subject
          }
        }
      },
    ]
  })
}

resource "aws_iam_role" "ci_push" {
  name               = "pokeclip-prod-ci-ecr-push"
  path               = "/pokeclip/prod/"
  description        = "GitHub Actions prod-deploy - push images to pokeclip-prod ECR"
  assume_role_policy = local.ci_assume_role_policy
}

# 권한은 prod/edge 의 aws_iam_role_policy.apex_upload 가 붙인다.
resource "aws_iam_role" "apex_upload" {
  name               = "pokeclip-prod-ci-apex-upload"
  path               = "/pokeclip/prod/"
  description        = "GitHub Actions prod-deploy - upload apex landing (policy in prod/edge)"
  assume_role_policy = local.ci_assume_role_policy
}

# 푸시 최소 권한이다. 삭제 · 저장소 정책 · 설정 동작은 두지 않는다.
# BatchGetImage 는 AWS 푸시 예시 정책(ECR 사용 설명서 image-push-iam)대로
# 넣는다. DescribeImages 는 푸시 뒤 다이제스트 · 같은 태그 확인이다.
resource "aws_iam_role_policy" "ci_push" {
  name = "pokeclip-prod-ci-ecr-push"
  role = aws_iam_role.ci_push.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "GetEcrAuthorizationToken"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "PushProdImages"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeImages",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
        ]
        Resource = [for repository in aws_ecr_repository.this : repository.arn]
      },
    ]
  })
}
