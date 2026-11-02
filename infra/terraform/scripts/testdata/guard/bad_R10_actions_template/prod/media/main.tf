# R10: 정책 문서 statement 의 동작을 템플릿 지시자로 적었다 — `서비스:동작` 꼴이
# 아니라 rds-db:connect 를 덮는지 대조할 수 없다.
data "aws_iam_policy_document" "media" {
  statement {
    actions   = ["%{if true}rds-db:connect%{endif}"]
    resources = ["*"]
  }
}
