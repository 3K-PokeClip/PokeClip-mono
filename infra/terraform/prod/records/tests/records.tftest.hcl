# prod/records 를 mock provider 로 단언한다(계획 M11 c1).
#
# - 공개 존은 이름 조회(pokeclip.com · 공개 존)로 얻고, 모든 레코드를 그 존에
#   둔다.
# - enabled_records 의 이름마다 그 이름의 레코드만 생긴다(apex · www = A +
#   AAAA, 나머지 = A). 기본값 [] 은 레코드 0 이다.
# - 이름마다 별칭 대상이 정해져 있다(apex · www = apex 배포, media = media
#   배포, app · api = 공개 ALB, ingest = NLB).
# - 허용 밖 이름 · 중복은 변수 검증이, 앞 루트 출력의 꼴이 어긋나면
#   terraform_data.remote_contract 의 precondition 이 멈춘다.
#
# 앞 루트(platform · edge) 상태는 override_data 로 흉내 낸다 — edge 출력
# 이름은 갈래 F 가 낼 계약 이름이다. 존 id · DNS 이름은 가짜 값이고, 대상이
# 뒤바뀌면 값이 달라 실패하도록 대상마다 다른 존 id 를 준다.

mock_provider "aws" {}

override_data {
  target = data.aws_route53_zone.public
  values = {
    zone_id = "Z0ZONEEXAMPLE"
  }
}

override_data {
  target = data.terraform_remote_state.platform
  values = {
    outputs = {
      public_alb_dns_name = "pokeclip-prod-public-1234567890.ap-northeast-2.elb.amazonaws.com"
      public_alb_zone_id  = "Z0ALBEXAMPLE"
      nlb_dns_name        = "pokeclip-prod-srt-0123456789abcdef.elb.ap-northeast-2.amazonaws.com"
      nlb_zone_id         = "Z0NLBEXAMPLE"
    }
  }
}

override_data {
  target = data.terraform_remote_state.edge
  values = {
    outputs = {
      apex_distribution_domain   = "d0apexexample.cloudfront.net"
      apex_distribution_zone_id  = "Z0APEXCFEXAMPLE"
      media_distribution_domain  = "d0mediaexample.cloudfront.net"
      media_distribution_zone_id = "Z0MEDIACFEXAMPLE"
    }
  }
}

run "looks_up_public_zone_by_name" {
  command = plan

  assert {
    condition     = data.aws_route53_zone.public.name == "pokeclip.com" && data.aws_route53_zone.public.private_zone == false
    error_message = "존 조회가 공개 존 pokeclip.com 이 아니다."
  }
}

# 머지 뒤 실수 apply 로 공개 이름이 열리지 않게 기본값은 레코드 0 이다.
run "default_creates_no_records" {
  command = plan

  assert {
    condition     = length(aws_route53_record.alias) == 0
    error_message = "enabled_records 기본값에서 레코드가 생긴다(기본은 0 이어야 한다)."
  }
}

run "apex_creates_a_and_aaaa" {
  command = plan

  variables {
    enabled_records = ["apex"]
  }

  assert {
    condition     = length(aws_route53_record.alias) == 2
    error_message = "apex 의 레코드 수가 2(A · AAAA)가 아니다."
  }

  assert {
    condition = (
      aws_route53_record.alias["apex-A"].name == "pokeclip.com" &&
      aws_route53_record.alias["apex-A"].type == "A" &&
      aws_route53_record.alias["apex-AAAA"].name == "pokeclip.com" &&
      aws_route53_record.alias["apex-AAAA"].type == "AAAA"
    )
    error_message = "apex 레코드의 이름 · 유형이 pokeclip.com A · AAAA 가 아니다."
  }
}

run "www_creates_a_and_aaaa" {
  command = plan

  variables {
    enabled_records = ["www"]
  }

  assert {
    condition     = length(aws_route53_record.alias) == 2
    error_message = "www 의 레코드 수가 2(A · AAAA)가 아니다."
  }

  assert {
    condition = (
      aws_route53_record.alias["www-A"].name == "www.pokeclip.com" &&
      aws_route53_record.alias["www-AAAA"].name == "www.pokeclip.com" &&
      aws_route53_record.alias["www-AAAA"].type == "AAAA"
    )
    error_message = "www 레코드의 이름 · 유형이 www.pokeclip.com A · AAAA 가 아니다."
  }
}

run "media_creates_a_only" {
  command = plan

  variables {
    enabled_records = ["media"]
  }

  assert {
    condition     = length(aws_route53_record.alias) == 1
    error_message = "media 의 레코드 수가 1(A)이 아니다."
  }

  assert {
    condition     = keys(aws_route53_record.alias) == ["media-A"]
    error_message = "media 의 레코드 키가 media-A 하나가 아니다."
  }
}

run "all_names_create_eight_records" {
  command = plan

  variables {
    enabled_records = ["apex", "www", "app", "api", "media", "ingest"]
  }

  assert {
    condition     = length(aws_route53_record.alias) == 8
    error_message = "여섯 이름 전부의 레코드 수가 8 이 아니다."
  }

  assert {
    condition = toset(keys(aws_route53_record.alias)) == toset([
      "apex-A", "apex-AAAA", "www-A", "www-AAAA", "app-A", "api-A", "media-A", "ingest-A",
    ])
    error_message = "여섯 이름 전부의 레코드 키 집합이 다르다."
  }

  assert {
    condition = alltrue([
      for record in aws_route53_record.alias : record.zone_id == "Z0ZONEEXAMPLE"
    ])
    error_message = "레코드가 조회한 공개 존에 있지 않다."
  }

  assert {
    condition = alltrue([
      for record in aws_route53_record.alias :
      one(record.alias).evaluate_target_health == false
    ])
    error_message = "레코드가 별칭(evaluate_target_health = false)이 아니다."
  }

  assert {
    condition = alltrue([
      for key in ["apex-A", "apex-AAAA", "www-A", "www-AAAA"] :
      one(aws_route53_record.alias[key].alias).name == "d0apexexample.cloudfront.net" &&
      one(aws_route53_record.alias[key].alias).zone_id == "Z0APEXCFEXAMPLE"
    ])
    error_message = "apex · www 의 별칭 대상이 apex 배포가 아니다."
  }

  assert {
    condition = (
      one(aws_route53_record.alias["media-A"].alias).name == "d0mediaexample.cloudfront.net" &&
      one(aws_route53_record.alias["media-A"].alias).zone_id == "Z0MEDIACFEXAMPLE"
    )
    error_message = "media 의 별칭 대상이 media 배포가 아니다."
  }

  assert {
    condition = alltrue([
      for key in ["app-A", "api-A"] :
      aws_route53_record.alias[key].name == "${split("-", key)[0]}.pokeclip.com" &&
      one(aws_route53_record.alias[key].alias).name == "pokeclip-prod-public-1234567890.ap-northeast-2.elb.amazonaws.com" &&
      one(aws_route53_record.alias[key].alias).zone_id == "Z0ALBEXAMPLE"
    ])
    error_message = "app · api 의 이름이 다르거나 별칭 대상이 공개 ALB 가 아니다."
  }

  assert {
    condition = (
      aws_route53_record.alias["ingest-A"].name == "ingest.pokeclip.com" &&
      one(aws_route53_record.alias["ingest-A"].alias).name == "pokeclip-prod-srt-0123456789abcdef.elb.ap-northeast-2.amazonaws.com" &&
      one(aws_route53_record.alias["ingest-A"].alias).zone_id == "Z0NLBEXAMPLE"
    )
    error_message = "ingest 의 이름이 다르거나 별칭 대상이 NLB 가 아니다."
  }
}

run "rejects_unknown_name" {
  command = plan

  variables {
    enabled_records = ["apex", "dev"]
  }

  expect_failures = [var.enabled_records]
}

run "rejects_duplicate_name" {
  command = plan

  variables {
    enabled_records = ["media", "media"]
  }

  expect_failures = [var.enabled_records]
}

# 앞 루트 출력의 꼴 — 이름이 어긋나 빠지거나(갈래 간 계약 이름) 꼴이 다르면
# 레코드를 만들기 전에 멈춘다. 레코드가 0 인 기본값에서도 본다.
run "remote_contract_rejects_missing_edge_output" {
  command = plan

  override_data {
    target = data.terraform_remote_state.edge
    values = {
      outputs = {
        apex_distribution_domain   = "d0apexexample.cloudfront.net"
        apex_distribution_zone_id  = "Z0APEXCFEXAMPLE"
        media_distribution_zone_id = "Z0MEDIACFEXAMPLE"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "remote_contract_rejects_non_cloudfront_apex_domain" {
  command = plan

  override_data {
    target = data.terraform_remote_state.edge
    values = {
      outputs = {
        apex_distribution_domain   = "pokeclip-prod-apex.s3.amazonaws.com"
        apex_distribution_zone_id  = "Z0APEXCFEXAMPLE"
        media_distribution_domain  = "d0mediaexample.cloudfront.net"
        media_distribution_zone_id = "Z0MEDIACFEXAMPLE"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "remote_contract_rejects_bad_alb_zone_id" {
  command = plan

  override_data {
    target = data.terraform_remote_state.platform
    values = {
      outputs = {
        public_alb_dns_name = "pokeclip-prod-public-1234567890.ap-northeast-2.elb.amazonaws.com"
        public_alb_zone_id  = ""
        nlb_dns_name        = "pokeclip-prod-srt-0123456789abcdef.elb.ap-northeast-2.amazonaws.com"
        nlb_zone_id         = "Z0NLBEXAMPLE"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

# 내부 ALB 도 DNS 이름 꼴은 같고 이름 앞에 internal- 이 붙는다 — app · api 를
# 내부 ALB 에 잇는 오연결이다.
run "remote_contract_rejects_internal_alb_dns_name" {
  command = plan

  override_data {
    target = data.terraform_remote_state.platform
    values = {
      outputs = {
        public_alb_dns_name = "internal-pokeclip-prod-internal-1234567890.ap-northeast-2.elb.amazonaws.com"
        public_alb_zone_id  = "Z0ALBEXAMPLE"
        nlb_dns_name        = "pokeclip-prod-srt-0123456789abcdef.elb.ap-northeast-2.amazonaws.com"
        nlb_zone_id         = "Z0NLBEXAMPLE"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "remote_contract_rejects_alb_name_as_nlb" {
  command = plan

  override_data {
    target = data.terraform_remote_state.platform
    values = {
      outputs = {
        public_alb_dns_name = "pokeclip-prod-public-1234567890.ap-northeast-2.elb.amazonaws.com"
        public_alb_zone_id  = "Z0ALBEXAMPLE"
        nlb_dns_name        = "pokeclip-prod-public-1234567890.ap-northeast-2.elb.amazonaws.com"
        nlb_zone_id         = "Z0NLBEXAMPLE"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

# 이름을 연 뒤에도 빠진 출력은 계약 검사에서만 멈춘다 — 레코드가 그 검사를
# 기다리므로(depends_on) null 별칭 대상으로 평가되지 않는다. 기다리지 않으면
# 레코드의 「Missing required argument」 가 함께 나 이 run 이 실패한다.
run "remote_contract_stops_before_records" {
  command = plan

  variables {
    enabled_records = ["apex", "www", "app", "api", "media", "ingest"]
  }

  override_data {
    target = data.terraform_remote_state.edge
    values = {
      outputs = {
        apex_distribution_domain   = "d0apexexample.cloudfront.net"
        apex_distribution_zone_id  = "Z0APEXCFEXAMPLE"
        media_distribution_zone_id = "Z0MEDIACFEXAMPLE"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}
