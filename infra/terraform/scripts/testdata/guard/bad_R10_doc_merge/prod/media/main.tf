# R10: 정책 문서가 다른 JSON 을 합친다 — 합쳐진 문은 대조를 비켜 간다.
variable "extra_policy_json" {
  type = string
}

data "aws_iam_policy_document" "media" {
  source_policy_documents = [var.extra_policy_json]

  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
}
