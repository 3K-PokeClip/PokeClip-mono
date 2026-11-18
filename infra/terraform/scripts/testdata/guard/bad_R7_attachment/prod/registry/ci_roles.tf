# R7 위반(꼴 2 — 관리형 정책 연결): registry 가 apex 업로드 역할에 관리형
# 정책을 연결한다.
resource "aws_iam_role" "apex_upload" {
  name               = "pokeclip-prod-ci-apex-upload"
  path               = "/pokeclip/prod/"
  assume_role_policy = "{}"
}

resource "aws_iam_role_policy_attachment" "apex_upload" {
  role       = aws_iam_role.apex_upload.id
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
}
