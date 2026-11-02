# 표준 로그 v2 — 배포 접근 로그를 us-east-1 CloudWatch Logs 로그 그룹에 JSON 으로
# 보낸다(설계 7절 지표 필터: cs-uri-stem · sc-status). log_destination 이 null 이면
# 전달 자원을 만들지 않는다. 로그 그룹 자원 정책은 전달을 만들 때 AWS 가 만든다
# (실행자에게 logs:PutResourcePolicy 등이 있으면 — CloudWatch Logs 안내서 「Logs
# sent to CloudWatch Logs」).

locals {
  # 기록할 필드 — CloudFront 표준 로그 필드(개발자 안내서 「Standard logging
  # reference」 · 2026-10-24 확인)에서 cs(Cookie) 를 뺀 명시 목록. 표준 로그에는
  # Authorization 필드가 없고, v2 가 더하는 실시간 필드(요청 헤더 전체 등)는
  # 넣지 않는다.
  log_record_fields = [
    "date",
    "time",
    "x-edge-location",
    "sc-bytes",
    "c-ip",
    "cs-method",
    "cs(Host)",
    "cs-uri-stem",
    "sc-status",
    "cs(Referer)",
    "cs(User-Agent)",
    "cs-uri-query",
    "x-edge-result-type",
    "x-edge-request-id",
    "x-host-header",
    "cs-protocol",
    "cs-bytes",
    "time-taken",
    "x-forwarded-for",
    "ssl-protocol",
    "ssl-cipher",
    "x-edge-response-result-type",
    "cs-protocol-version",
    "fle-status",
    "fle-encrypted-fields",
    "c-port",
    "time-to-first-byte",
    "x-edge-detailed-result-type",
    "sc-content-type",
    "sc-content-len",
    "sc-range-start",
    "sc-range-end",
  ]
}

resource "aws_cloudwatch_log_delivery_source" "this" {
  count = var.log_destination == null ? 0 : 1

  name         = "${local.name_prefix}-media-access"
  log_type     = "ACCESS_LOGS"
  resource_arn = aws_cloudfront_distribution.this.arn
}

resource "aws_cloudwatch_log_delivery_destination" "this" {
  count = var.log_destination == null ? 0 : 1

  name          = "${local.name_prefix}-media-access"
  output_format = "json"

  delivery_destination_configuration {
    destination_resource_arn = var.log_destination
  }
}

resource "aws_cloudwatch_log_delivery" "this" {
  count = var.log_destination == null ? 0 : 1

  delivery_source_name     = aws_cloudwatch_log_delivery_source.this[0].name
  delivery_destination_arn = aws_cloudwatch_log_delivery_destination.this[0].arn
  record_fields            = local.log_record_fields
}
