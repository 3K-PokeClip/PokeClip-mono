# R10: 인라인 정책의 role 이 같은 경로의 역할 참조가 아니라 다른 서비스 역할
# 이름 리터럴이다.
data "aws_iam_policy_document" "media" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "media" {
  role   = "pokeclip-prod-auth-task"
  policy = data.aws_iam_policy_document.media.json
}
