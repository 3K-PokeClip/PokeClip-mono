# 옛 존 import 구성의 선언을 mock provider 로 단언한다.
# 무변경(No changes)은 tftest 로 볼 수 없어 운영 O1 plan 이 판정한다.

mock_provider "aws" {}

# mock provider 는 import 를 못 하므로 import 대상마다 override 를 둔다.
override_resource {
  target          = aws_route53_zone.legacy
  override_during = plan
  values = {
    name_servers = ["ns-1.example.net", "ns-2.example.org"]
  }
}

override_resource {
  target          = aws_route53_record.ns
  override_during = plan
}

override_resource {
  target          = aws_route53_record.legacy
  override_during = plan
}

variables {
  zone_id      = "Z0123456789EXAMPLE"
  zone_comment = "example"
  zone_tags    = {}
  ns_ttl       = 172800
  records = {
    "dev_A" = {
      name    = "dev.pokeclip.com"
      type    = "A"
      ttl     = 300
      records = ["192.0.2.10"]
    }
    "media-dev_A" = {
      name    = "media-dev.pokeclip.com"
      type    = "A"
      ttl     = 300
      records = ["192.0.2.20"]
    }
  }
}

run "imports_zone_and_records" {
  command = plan

  assert {
    condition     = aws_route53_record.ns.ttl == var.ns_ttl
    error_message = "옛 존 NS 레코드 TTL 이 ns_ttl 이 아니다."
  }

  # 존 name_servers 는 끝 점이 없고 실제 NS 레코드 값은 끝 점이 있다 — 그대로
  # 넘기면 O1 plan 이 값 수정을 낸다.
  assert {
    condition = (
      length(aws_route53_record.ns.records) == length(aws_route53_zone.legacy.name_servers) &&
      alltrue([for ns in aws_route53_record.ns.records : endswith(ns, ".")])
    )
    error_message = "NS 레코드 값이 네임서버마다 끝 점(.)으로 끝나는 하나씩이 아니다."
  }

  assert {
    condition     = aws_route53_record.ns.records == toset(["ns-1.example.net.", "ns-2.example.org."])
    error_message = "NS 레코드 값이 존 네임서버에 끝 점을 붙인 값이 아니다."
  }

  assert {
    condition     = aws_route53_record.ns.name == "pokeclip.com" && aws_route53_record.ns.type == "NS"
    error_message = "NS 레코드가 존 apex NS 가 아니다."
  }

  assert {
    condition     = toset(keys(aws_route53_record.legacy)) == toset(keys(var.records))
    error_message = "레코드 선언 키 집합이 records 입력과 다르다."
  }

  assert {
    condition = alltrue([
      for key, record in aws_route53_record.legacy :
      record.name == var.records[key].name && record.type == var.records[key].type &&
      record.ttl == var.records[key].ttl && record.records == toset(var.records[key].records)
    ])
    error_message = "레코드 값이 records 입력과 다르다."
  }

  assert {
    condition     = output.name_servers == tolist(["ns-1.example.net", "ns-2.example.org"])
    error_message = "출력 name_servers 가 존 네임서버가 아니다."
  }

  assert {
    condition     = toset(keys(output.records)) == toset(keys(var.records))
    error_message = "출력 records 의 키 집합이 records 입력과 다르다."
  }

  assert {
    condition     = output.records["dev_A"].records == toset(["192.0.2.10"])
    error_message = "출력 records 의 값이 레코드 값과 다르다."
  }
}

# 네임서버 값에 이미 끝 점이 있어도 점을 겹쳐 붙이지 않는다.
run "keeps_single_trailing_dot_on_ns_records" {
  command = plan

  override_resource {
    target          = aws_route53_zone.legacy
    override_during = plan
    values = {
      name_servers = ["ns-1.example.net.", "ns-2.example.org"]
    }
  }

  assert {
    condition     = aws_route53_record.ns.records == toset(["ns-1.example.net.", "ns-2.example.org."])
    error_message = "끝 점이 이미 있는 네임서버에 점이 겹쳐 붙었다."
  }
}

run "lowers_ns_ttl_by_variable" {
  command = plan

  variables {
    ns_ttl = 900
  }

  assert {
    condition     = aws_route53_record.ns.ttl == 900
    error_message = "ns_ttl 을 바꿔도 NS 레코드 TTL 이 따라가지 않는다."
  }
}

run "rejects_apex_ns_in_records" {
  command = plan

  variables {
    records = {
      "apex_NS" = {
        name    = "pokeclip.com"
        type    = "NS"
        ttl     = 172800
        records = ["ns-1.example.net"]
      }
    }
  }

  expect_failures = [var.records]
}

run "rejects_soa_in_records" {
  command = plan

  variables {
    records = {
      "apex_SOA" = {
        name    = "pokeclip.com"
        type    = "SOA"
        ttl     = 900
        records = ["ns-1.example.net. awsdns-hostmaster.amazon.com. 1 7200 900 1209600 86400"]
      }
    }
  }

  expect_failures = [var.records]
}
