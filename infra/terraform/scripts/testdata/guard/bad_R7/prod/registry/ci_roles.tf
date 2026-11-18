# R7 위반(꼴 1 — 인라인 정책 자원): registry 가 apex 업로드 역할에 인라인
# 정책을 붙인다. apex 권한은 prod/edge 가 붙인다.
resource "aws_iam_role" "apex_upload" {
  name               = "pokeclip-prod-ci-apex-upload"
  path               = "/pokeclip/prod/"
  assume_role_policy = "{}"
}

resource "aws_iam_role_policy" "apex_upload" {
  name   = "pokeclip-prod-ci-apex-upload"
  role   = aws_iam_role.apex_upload.name
  policy = "{}"
}
