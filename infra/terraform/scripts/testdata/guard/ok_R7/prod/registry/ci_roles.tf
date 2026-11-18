# R7 통과: registry 는 CI 역할 둘의 신원 · 신뢰와 푸시 역할의 인라인 정책만
# 갖는다. apex 업로드 역할의 권한은 prod/edge 가 붙인다.
resource "aws_iam_role" "ci_push" {
  name               = "pokeclip-prod-ci-ecr-push"
  path               = "/pokeclip/prod/"
  assume_role_policy = "{}"
}

resource "aws_iam_role" "apex_upload" {
  name               = "pokeclip-prod-ci-apex-upload"
  path               = "/pokeclip/prod/"
  assume_role_policy = "{}"
}

resource "aws_iam_role_policy" "ci_push" {
  name   = "pokeclip-prod-ci-ecr-push"
  role   = aws_iam_role.ci_push.name
  policy = "{}"
}
