# R10: 정책 문서 statement 에 iam 쓰기 동작이 있다(역할에 정책을 붙여 권한을
# 올린다).
data "aws_iam_policy_document" "media" {
  statement {
    actions   = ["iam:PutRolePolicy"]
    resources = ["*"]
  }
}
