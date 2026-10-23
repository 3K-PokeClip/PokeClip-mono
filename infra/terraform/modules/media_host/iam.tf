# Media 인스턴스 역할(설계 3-3 · 3-3-1 · 계획 M9 c1). 정책은 역할을 만드는 이
# 루트가 붙인다. Resource 는 모두 정확한 ARN 이고, `*` 는 GetAuthorizationToken
# (자원 지정 불가)과 rewind 버킷 객체뿐이다. 관리형 정책은 SSM Core 하나다 —
# guard R10 이 그 밖의 관리형 정책 · dbuser 와일드카드를 막는다.

locals {
  # ECR 읽기 대상 — Media 상자 이미지 넷 · 루프백 송출기 · 설치 묶음.
  ecr_repository_keys = [
    "media-nginx",
    "media-mediamtx",
    "media-sidecar",
    "media-ingest-auth",
    "media-loopback-sender",
    "media-host-bundle",
  ]

  # 컨테이너마다 DB 사용자를 따로 둔다(사이드카 · janitor — 둘 다 rds_iam).
  db_users = ["media_indexer", "media_janitor"]

  managed_policy_arns = {
    AmazonSSMManagedInstanceCore = "arn:${local.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }
}

resource "aws_iam_role" "media" {
  name = "${var.name}-media"
  path = var.iam_path
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_instance_profile" "media" {
  name = "${var.name}-media"
  path = var.iam_path
  role = aws_iam_role.media.name
}

resource "aws_iam_role_policy_attachment" "managed" {
  for_each = local.managed_policy_arns

  role       = aws_iam_role.media.name
  policy_arn = each.value
}

data "aws_iam_policy_document" "media" {
  # 시작 스크립트(CDN 비밀) · 루프백(시험 키) · 런타임 비밀.
  statement {
    sid     = "SecretsRead"
    actions = ["secretsmanager:GetSecretValue"]
    resources = distinct(concat(
      [var.cdn_secret_arn, var.test_stream_key_secret_arn],
      var.media_secret_arns,
    ))
  }

  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [
      for key in local.ecr_repository_keys :
      "arn:${local.partition}:ecr:${local.region}:${local.account_id}:repository/${var.name}/${key}"
    ]
  }

  statement {
    sid     = "DbConnect"
    actions = ["rds-db:connect"]
    resources = [
      for user in local.db_users :
      "arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.rds_resource_id}/${user}"
    ]
  }

  # 사이드카 업로드 · 발행(PutObject · HeadObject · GetObject — HEAD 는 GetObject
  # 권한으로 된다).
  statement {
    sid       = "RewindObjects"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${var.rewind_bucket_arn}/*"]
  }

  # 사이드카 기동 때 HeadBucket(s3:ListBucket 필요) — 없으면 rewind 조립이 실패하고,
  # 없는 키의 HEAD 가 404 가 아니라 403 이 된다.
  statement {
    sid       = "RewindBucket"
    actions   = ["s3:ListBucket"]
    resources = [var.rewind_bucket_arn]
  }

  # 응답기 거부 목록(String — KMS 불요).
  statement {
    sid       = "DenylistRead"
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.denylist.arn]
  }
}

resource "aws_iam_role_policy" "media" {
  name   = "${var.name}-media"
  role   = aws_iam_role.media.id
  policy = data.aws_iam_policy_document.media.json
}
