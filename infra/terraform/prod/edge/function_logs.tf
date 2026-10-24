# CloudFront Function 로그 그룹(POK-469 · PR 387 보안 L2) — 함수의
# console.log 줄이 가는 로그 그룹을 미리 만들어 보존을 건다. 두지 않으면 AWS
# 가 첫 로그 때 보존 없음(무기한)으로 만든다.
#
# 이름 · 리전은 CloudFront 개발자 안내서 「Edge function logs」의 「CloudFront
# Functions logs」(2026-10-25 확인)를 따른다 — 캐시 비헤이비어 함수의 로그
# 그룹은 /aws/cloudfront/function/<함수 이름> 이고, 어느 엣지에서 돌든
# us-east-1 에 쌓인다. 그래서 기본(us-east-1) provider 로 만든다.
# https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/edge-functions-logs.html
#
# 함수 이름은 두 모듈의 출력에서 받는다. 이 그룹보다 함수가 먼저 로그를
# 내면 apply 가 「이미 있음」으로 멈춘다 — README 「적용 순서(O7f)」.

locals {
  # 보존은 WAF 차단 로그와 같다(PR 387 보안 L2).
  function_log_retention_days = local.waf_log_retention_days

  cloudfront_function_names = {
    live_auth    = module.media.function_names.live_auth
    deny_default = module.media.function_names.deny_default
    apex_request = module.apex.function_name
  }
}

resource "aws_cloudwatch_log_group" "cloudfront_function" {
  for_each = local.cloudfront_function_names

  name              = "/aws/cloudfront/function/${each.value}"
  retention_in_days = local.function_log_retention_days
}
