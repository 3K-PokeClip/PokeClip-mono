# R13: ARN 속 12자리 계정 ID 리터럴(시험용 가짜 값).
data "aws_iam_policy_document" "example" {
  statement {
    actions   = ["sts:AssumeRole"]
    resources = ["arn:aws:iam::123456789012:role/x"]
  }
}
