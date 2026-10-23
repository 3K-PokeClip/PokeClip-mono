# R10: 역할에 허용 속성 밖의 키(permissions_boundary)가 있다.
variable "boundary_arn" {
  type = string
}

resource "aws_iam_role" "media" {
  name                 = "pokeclip-prod-media"
  path                 = "/pokeclip/prod/"
  permissions_boundary = var.boundary_arn
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }]
  })
}
