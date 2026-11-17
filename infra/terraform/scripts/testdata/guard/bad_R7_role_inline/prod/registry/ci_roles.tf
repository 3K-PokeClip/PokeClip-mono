# R7 위반(꼴 3 — 역할 본문의 정책 인자): apex 업로드 역할 본문에
# inline_policy 블록을 둔다.
resource "aws_iam_role" "apex_upload" {
  name               = "pokeclip-prod-ci-apex-upload"
  path               = "/pokeclip/prod/"
  assume_role_policy = "{}"

  inline_policy {
    name   = "apex-upload"
    policy = "{}"
  }
}
