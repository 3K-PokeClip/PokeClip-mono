# R10: 역할에 dynamic "inline_policy" 로 정책 문서 밖 권한을 붙인다.
variable "policies" {
  type = map(string)
}

resource "aws_iam_role" "media" {
  name = "pokeclip-prod-media"
  path = "/pokeclip/prod/"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }]
  })

  dynamic "inline_policy" {
    for_each = var.policies

    content {
      name   = inline_policy.key
      policy = inline_policy.value
    }
  }
}
