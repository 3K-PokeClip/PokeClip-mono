# rewind 버킷 정책 — media 배포가 OAC 로 되감기 · VOD 객체를 읽는 허용문
# (CONTRACT-runtime.md 「edge → 런타임 2단」). 버킷은 런타임 1단
# (prod/runtime/rewind_bucket.tf)이 만들고, 배포 ARN 은 edge 가 만들어서 정책만
# edge 뒤의 이 루트가 둔다.
#
# 자원은 dvr/* · vod/* 둘뿐이다. media 배포의 /dvr/* · /vod/* 비헤이비어는
# 오리진 경로 · 함수 없이 요청 경로를 그대로 객체 키로 쓴다
# (modules/cdn_media/main.tf). 같은 버킷의 원본 조각(streams/*)은 CDN 으로 열지
# 않는다 — 편집기 미리 듣기는 clip 이 서명한 주소로 S3 에 직접 간다.
#
# 버킷 정책은 버킷당 하나다. 이 자원 밖에서 rewind 버킷 정책을 두면 서로를
# 덮어쓴다 — 문장을 더할 때는 이 자원의 Statement 에 더한다. 1단은 이 버킷에
# 정책을 두지 않는다(그 파일의 「두지 않는 것」).
#
# 두지 않는 것:
#   - s3:ListBucket: 없는 키는 404 가 아니라 403 이다(apex 와 같음).
#   - Deny 문장: clip · 사이드카 작업 역할을 막지 않아야 한다(계약 「작업
#     역할 대상」). TLS 거부(K8)는 후속이고, 더하면 같은 자원에 넣는다.

locals {
  # CDN 이 읽는 객체 키 접두(되감기 목록 · 조각 · init 과 VOD 목록).
  rewind_cdn_key_prefixes = ["dvr", "vod"]
}

resource "aws_s3_bucket_policy" "rewind" {
  bucket = local.runtime.rewind_bucket_name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowMediaCloudFrontOacRead"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource = [
          for prefix in local.rewind_cdn_key_prefixes :
          "${local.runtime.rewind_bucket_arn}/${prefix}/*"
        ]
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = local.edge.media_distribution_arn
          }
        }
      },
    ]
  })

  depends_on = [terraform_data.remote_contract]
}
