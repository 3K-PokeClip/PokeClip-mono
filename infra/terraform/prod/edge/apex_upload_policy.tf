# apex 랜딩 업로드 권한(prod CI B12) — prod/registry 의 apex 업로드 역할
# (신원 · 신뢰)에 이 루트가 권한을 붙인다. 버킷 · 배포가 이 루트에 있어서다.
# 되돌릴 때는 이 정책을 먼저 지운다(그다음 registry 역할).
#
# 업로드는 목록 · 올리기 · 무효화뿐이다. sync 에 --delete 를 쓰지 않으므로
# 지우기 동작(s3:DeleteObject*)을 두지 않고, ACL · 버킷 설정 · 객체 읽기도
# 두지 않는다. 버킷 암호화는 SSE-S3(AES256)라 KMS 권한이 필요 없다.

locals {
  # 모듈은 이름 · id 만 내보낸다. ARN 은 plan 때 아는 값으로 짓는다.
  apex_bucket_arn = format(
    "arn:%s:s3:::%s",
    data.aws_partition.current.partition,
    module.apex.bucket_name,
  )
  apex_distribution_arn = format(
    "arn:%s:cloudfront::%s:distribution/%s",
    data.aws_partition.current.partition,
    data.aws_caller_identity.current.account_id,
    module.apex.distribution_id,
  )
}

# 역할 이름은 registry 출력이다. 출력이 없으면 속성 오류가 아니라
# remote_contract 의 계약 문구로 멈추게 null 로 두고 그 검사를 기다린다.
resource "aws_iam_role_policy" "apex_upload" {
  name = "pokeclip-prod-ci-apex-upload"
  role = try(local.registry.apex_upload_role_name, null)
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListApexBucket"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = local.apex_bucket_arn
      },
      {
        Sid      = "PutApexObjects"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = "${local.apex_bucket_arn}/*"
      },
      {
        Sid    = "InvalidateApexDistribution"
        Effect = "Allow"
        Action = [
          "cloudfront:CreateInvalidation",
          "cloudfront:GetInvalidation",
        ]
        Resource = local.apex_distribution_arn
      },
    ]
  })

  depends_on = [terraform_data.remote_contract]
}
