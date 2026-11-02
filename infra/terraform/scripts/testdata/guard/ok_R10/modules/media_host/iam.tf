# R10 을 지키는 꼴: rds-db:connect 는 DB 사용자 둘로 좁히고, 관리형 정책은
# SSM Core 하나다.
data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

variable "rds_resource_id" {
  type = string
}

locals {
  db_users = ["media_indexer", "media_janitor"]
  managed_policy_arns = {
    AmazonSSMManagedInstanceCore = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }
}

data "aws_iam_policy_document" "media" {
  statement {
    actions = ["rds-db:connect"]
    resources = [
      for u in local.db_users :
      "arn:${data.aws_partition.current.partition}:rds-db:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/${u}"
    ]
  }

  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
}

resource "aws_iam_role" "media" {
  name = "pokeclip-prod-media"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy_attachment" "managed" {
  for_each = local.managed_policy_arns

  role       = aws_iam_role.media.name
  policy_arn = each.value
}
