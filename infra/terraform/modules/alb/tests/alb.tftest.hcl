# ALB 모듈(설계 3-3 의 alb · 계획 M8 c2 「c2 단언 목록」 · P1 규칙 분할
# 한도)을 mock provider 로 단언한다.
#
# 기본 변수는 내부 ALB 꼴이다(instance 대상 · 헬스 포트 = 별도 포트 ·
# /live · /playback 경로 바꿈). public_* 실행은 공개 ALB 꼴(ip 대상 · host
# 규칙 · 접두사 분할)로 바꿔 돈다. SG 규칙을 모듈이 만들지 않는다는 것(내부
# ALB → Media 이그레스는 media 루트 소유 — 설계 5-5)은 tftest 로 볼 수 없어
# 리뷰가 확인한다. id · ARN · 이름은 가짜 값이다(인증서 ARN 의 계정 ID 는
# AWS 문서 예시 값 — provider 가 ARN 꼴을 검사한다).

mock_provider "aws" {}

# provider 가 리스너 · 규칙에 넘기는 ARN 의 꼴을 검사하므로, mock 이 만드는
# 임의 문자열 대신 ARN 꼴을 고정한다. 대상 그룹은 키마다 다른 값이라 규칙 →
# 대상 그룹 짝 어긋남을 잡는다.
override_resource {
  target = aws_lb.this
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:loadbalancer/app/example/0example"
  }
}

override_resource {
  target = aws_lb_listener.https
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:listener/app/example/0example/0https"
  }
}

override_resource {
  target = aws_lb_target_group.this["live"]
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-live/0live"
  }
}

override_resource {
  target = aws_lb_target_group.this["control"]
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-control/0control"
  }
}

override_resource {
  target = aws_lb_target_group.this["playback"]
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-playback/0playback"
  }
}

override_resource {
  target = aws_lb_target_group.this["next"]
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-next/0next"
  }
}

override_resource {
  target = aws_lb_target_group.this["auth"]
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-auth/0auth"
  }
}

variables {
  name            = "example-int"
  internal        = true
  vpc_id          = "vpc-0example"
  subnet_ids      = ["subnet-0examplea", "subnet-0examplec"]
  certificate_arn = "arn:aws:acm:ap-northeast-2:111122223333:certificate/example"

  target_groups = {
    live = {
      port        = 8443
      protocol    = "HTTP"
      target_type = "instance"
      health_check = {
        port = "8081"
        path = "/healthz"
      }
    }
    control = {
      port        = 8444
      protocol    = "HTTP"
      target_type = "instance"
      health_check = {
        port = "8081"
        path = "/healthz"
      }
    }
    playback = {
      port        = 8445
      protocol    = "HTTP"
      target_type = "instance"
      health_check = {
        port = "8081"
        path = "/healthz"
      }
    }
  }

  listener_rules = {
    live = {
      priority      = 10
      target_group  = "live"
      path_patterns = ["/live/*"]
      path_rewrite = {
        regex   = "^/live/(.*)$"
        replace = "/$1"
      }
    }
    rewind = {
      priority      = 20
      target_group  = "control"
      path_patterns = ["/api/streams/*/rewind"]
    }
    ingest = {
      priority      = 30
      target_group  = "control"
      path_patterns = ["/api/ingest/*"]
    }
    playback = {
      priority      = 40
      target_group  = "playback"
      path_patterns = ["/playback/*"]
      path_rewrite = {
        regex   = "^/playback/(.*)$"
        replace = "/$1"
      }
    }
  }
}

run "creates_internal_alb_in_two_subnets" {
  command = plan

  assert {
    condition     = aws_lb.this.internal && aws_lb.this.load_balancer_type == "application"
    error_message = "ALB 가 internal 입력대로 내부 ALB 가 아니다."
  }

  assert {
    condition     = aws_lb.this.subnets == toset(["subnet-0examplea", "subnet-0examplec"])
    error_message = "ALB 서브넷이 입력 subnet_ids(2 AZ)가 아니다."
  }

  assert {
    condition     = aws_lb.this.idle_timeout == 60
    error_message = "ALB idle_timeout 기본값이 60 이 아니다."
  }

  assert {
    condition     = aws_lb.this.name == "example-int"
    error_message = "ALB 이름이 입력 name 이 아니다."
  }
}

run "creates_internet_facing_alb_when_not_internal" {
  command = plan

  variables {
    internal     = false
    idle_timeout = 120
  }

  assert {
    condition     = !aws_lb.this.internal
    error_message = "internal = false 인데 ALB 가 인터넷 대면이 아니다."
  }

  assert {
    condition     = aws_lb.this.idle_timeout == 120
    error_message = "ALB idle_timeout 이 입력값이 아니다."
  }
}

# SG 는 모듈이 만들고 ALB 에 붙인다. 규칙은 호출하는 루트가 별도 자원으로
# 붙인다(guard R3 · 설계 5-5).
run "attaches_own_security_group" {
  command = apply

  assert {
    condition     = aws_lb.this.security_groups == toset([aws_security_group.this.id])
    error_message = "ALB 에 모듈의 SG 하나만 붙어 있지 않다."
  }

  assert {
    condition     = aws_security_group.this.vpc_id == "vpc-0example" && aws_security_group.this.name == "example-int-alb"
    error_message = "SG 가 입력 vpc_id 에 없거나 이름이 <name>-alb 가 아니다."
  }

  assert {
    condition     = output.sg_id == aws_security_group.this.id
    error_message = "출력 sg_id 가 모듈 SG 가 아니다."
  }
}

run "listens_https_443_with_certificate" {
  command = apply

  assert {
    condition = (
      aws_lb_listener.https.load_balancer_arn == aws_lb.this.arn &&
      aws_lb_listener.https.port == 443 &&
      aws_lb_listener.https.protocol == "HTTPS" &&
      aws_lb_listener.https.certificate_arn == "arn:aws:acm:ap-northeast-2:111122223333:certificate/example"
    )
    error_message = "리스너가 이 ALB 의 HTTPS 443 · 입력 certificate_arn 이 아니다."
  }
}

# 규칙에 걸리지 않은 요청은 대상에 닿지 않고 404 다(설계 3-3 「기본 동작은
# 전부 거절」). 입력으로 바꿀 수 없다.
run "defaults_listener_to_fixed_404" {
  command = plan

  assert {
    condition = (
      length(aws_lb_listener.https.default_action) == 1 &&
      aws_lb_listener.https.default_action[0].type == "fixed-response" &&
      aws_lb_listener.https.default_action[0].fixed_response[0].status_code == "404"
    )
    error_message = "443 리스너 기본 동작이 fixed-response 404 가 아니다."
  }

  assert {
    condition = alltrue([
      for action in aws_lb_listener.https.default_action :
      action.type != "forward" && length(action.forward) == 0 && action.target_group_arn == null
    ])
    error_message = "443 리스너 기본 동작에 forward 가 있다."
  }
}

# 대상 그룹 프로토콜 · 헬스 체크 포트 · 경로는 입력값 그대로다 — Media 대상
# 그룹은 traffic-port 가 아닌 헬스 포트를 본다(설계 3-3-1).
run "creates_target_groups_from_input_as_is" {
  command = plan

  assert {
    condition     = toset(keys(aws_lb_target_group.this)) == toset(["live", "control", "playback"])
    error_message = "대상 그룹 키 집합이 입력 target_groups 와 다르다."
  }

  assert {
    condition = alltrue([
      for key, group in var.target_groups :
      aws_lb_target_group.this[key].port == group.port &&
      aws_lb_target_group.this[key].protocol == group.protocol &&
      aws_lb_target_group.this[key].target_type == group.target_type &&
      aws_lb_target_group.this[key].vpc_id == "vpc-0example" &&
      aws_lb_target_group.this[key].name == "example-int-${key}"
    ])
    error_message = "대상 그룹 포트 · 프로토콜 · 대상 유형 · VPC · 이름이 입력과 다르다."
  }

  assert {
    condition = alltrue([
      for key, group in var.target_groups :
      aws_lb_target_group.this[key].health_check[0].enabled &&
      aws_lb_target_group.this[key].health_check[0].port == group.health_check.port &&
      aws_lb_target_group.this[key].health_check[0].path == group.health_check.path
    ])
    error_message = "대상 그룹 health_check 포트 · 경로가 입력값 그대로가 아니다."
  }

  assert {
    condition = alltrue([
      for group in aws_lb_target_group.this : group.deregistration_delay == "30"
    ])
    error_message = "대상 그룹 deregistration_delay 가 30 이 아니다."
  }
}

run "rewrites_live_and_playback_paths" {
  command = plan

  assert {
    condition = (
      length(aws_lb_listener_rule.this["live/0"].transform) == 1 &&
      one(aws_lb_listener_rule.this["live/0"].transform).type == "url-rewrite" &&
      one(aws_lb_listener_rule.this["live/0"].transform).url_rewrite_config[0].rewrite[0].regex == "^/live/(.*)$" &&
      one(aws_lb_listener_rule.this["live/0"].transform).url_rewrite_config[0].rewrite[0].replace == "/$1"
    )
    error_message = "/live 규칙의 transform 이 ^/live/(.*)$ → /$1 경로 바꿈이 아니다."
  }

  assert {
    condition = (
      length(aws_lb_listener_rule.this["playback/0"].transform) == 1 &&
      one(aws_lb_listener_rule.this["playback/0"].transform).type == "url-rewrite" &&
      one(aws_lb_listener_rule.this["playback/0"].transform).url_rewrite_config[0].rewrite[0].regex == "^/playback/(.*)$" &&
      one(aws_lb_listener_rule.this["playback/0"].transform).url_rewrite_config[0].rewrite[0].replace == "/$1"
    )
    error_message = "/playback 규칙의 transform 이 ^/playback/(.*)$ → /$1 경로 바꿈이 아니다."
  }

  # 제어면 경로는 바꾸지 않는다.
  assert {
    condition = (
      length(aws_lb_listener_rule.this["rewind/0"].transform) == 0 &&
      length(aws_lb_listener_rule.this["ingest/0"].transform) == 0
    )
    error_message = "path_rewrite 가 없는 규칙에 transform 이 있다."
  }
}

run "routes_internal_paths_to_target_groups" {
  command = apply

  assert {
    condition     = toset(keys(aws_lb_listener_rule.this)) == toset(["live/0", "rewind/0", "ingest/0", "playback/0"])
    error_message = "리스너 규칙 키 집합이 규칙마다 한 조각이 아니다."
  }

  assert {
    condition = (
      aws_lb_listener_rule.this["live/0"].action[0].target_group_arn == aws_lb_target_group.this["live"].arn &&
      aws_lb_listener_rule.this["rewind/0"].action[0].target_group_arn == aws_lb_target_group.this["control"].arn &&
      aws_lb_listener_rule.this["ingest/0"].action[0].target_group_arn == aws_lb_target_group.this["control"].arn &&
      aws_lb_listener_rule.this["playback/0"].action[0].target_group_arn == aws_lb_target_group.this["playback"].arn
    )
    error_message = "리스너 규칙이 입력 target_group 의 대상 그룹으로 보내지 않는다."
  }

  assert {
    condition = alltrue([
      for rule in aws_lb_listener_rule.this :
      rule.listener_arn == aws_lb_listener.https.arn && rule.action[0].type == "forward"
    ])
    error_message = "리스너 규칙이 443 리스너의 forward 규칙이 아니다."
  }

  assert {
    condition = (
      one([for c in aws_lb_listener_rule.this["live/0"].condition : c.path_pattern[0].values if length(c.path_pattern) > 0]) == toset(["/live/*"]) &&
      one([for c in aws_lb_listener_rule.this["rewind/0"].condition : c.path_pattern[0].values if length(c.path_pattern) > 0]) == toset(["/api/streams/*/rewind"]) &&
      one([for c in aws_lb_listener_rule.this["ingest/0"].condition : c.path_pattern[0].values if length(c.path_pattern) > 0]) == toset(["/api/ingest/*"]) &&
      one([for c in aws_lb_listener_rule.this["playback/0"].condition : c.path_pattern[0].values if length(c.path_pattern) > 0]) == toset(["/playback/*"])
    )
    error_message = "리스너 규칙 경로 패턴이 입력 path_patterns 그대로가 아니다."
  }

  assert {
    condition = (
      aws_lb_listener_rule.this["live/0"].priority == 10 &&
      aws_lb_listener_rule.this["rewind/0"].priority == 20 &&
      aws_lb_listener_rule.this["ingest/0"].priority == 30 &&
      aws_lb_listener_rule.this["playback/0"].priority == 40
    )
    error_message = "조각 하나인 규칙의 우선순위가 입력 priority 가 아니다."
  }
}

# 공개 ALB 꼴 — api 호스트 + 접두사 3개를 규칙당 경로 값 2개(기본)로 나눈다.
# 접두사마다 /x 와 /x/* 를 덮어 조각 셋이 된다(P1: 규칙당 조건 값 5 이하 ·
# 조건마다 3 이하).
run "public_splits_prefixes_into_consecutive_rules" {
  command = apply

  variables {
    name     = "example-pub"
    internal = false
    target_groups = {
      next = {
        port        = 3000
        protocol    = "HTTP"
        target_type = "ip"
        health_check = {
          port = "traffic-port"
          path = "/api/ping"
        }
      }
      auth = {
        port        = 8082
        protocol    = "HTTP"
        target_type = "ip"
        health_check = {
          port = "traffic-port"
          path = "/actuator/health"
        }
      }
    }
    listener_rules = {
      app = {
        priority     = 1
        target_group = "next"
        host_headers = ["app.example.com"]
      }
      auth = {
        priority      = 100
        target_group  = "auth"
        host_headers  = ["api.example.com"]
        path_prefixes = ["/api/auth", "/api/stream-keys", "/api/profile-photos"]
      }
    }
  }

  assert {
    condition     = toset(keys(aws_lb_listener_rule.this)) == toset(["app/0", "auth/0", "auth/1", "auth/2"])
    error_message = "규칙 집합이 app 한 조각 · auth 세 조각(접두사 3 × 값 2 ÷ 2)이 아니다."
  }

  assert {
    condition = toset(flatten([
      for key in ["auth/0", "auth/1", "auth/2"] : [
        for c in aws_lb_listener_rule.this[key].condition : c.path_pattern[0].values if length(c.path_pattern) > 0
      ]
      ])) == toset([
      "/api/auth", "/api/auth/*",
      "/api/stream-keys", "/api/stream-keys/*",
      "/api/profile-photos", "/api/profile-photos/*",
    ])
    error_message = "auth 조각의 경로 합집합이 접두사마다 /x · /x/* 가 아니다."
  }

  assert {
    condition = alltrue([
      for rule in aws_lb_listener_rule.this :
      sum(concat([0], [
        for c in rule.condition :
        (length(c.host_header) > 0 ? length(c.host_header[0].values) : 0) +
        (length(c.path_pattern) > 0 ? length(c.path_pattern[0].values) : 0)
        ])) <= 5 && alltrue([
        for c in rule.condition :
        (length(c.host_header) == 0 || length(c.host_header[0].values) <= 3) &&
        (length(c.path_pattern) == 0 || length(c.path_pattern[0].values) <= 3)
      ])
    ])
    error_message = "규칙의 조건 값 합이 5 를 넘거나 조건 하나의 값이 3 을 넘는다."
  }

  assert {
    condition = alltrue([
      for index in range(3) :
      aws_lb_listener_rule.this["auth/${index}"].priority == 100 + index &&
      aws_lb_listener_rule.this["auth/${index}"].action[0].target_group_arn == aws_lb_target_group.this["auth"].arn &&
      one([for c in aws_lb_listener_rule.this["auth/${index}"].condition : c.host_header[0].values if length(c.host_header) > 0]) == toset(["api.example.com"])
    ])
    error_message = "auth 조각의 우선순위가 연속이 아니거나 대상 그룹 · host 가 같지 않다."
  }

  # host 만 있는 규칙에는 경로 조건이 없다.
  assert {
    condition = (
      one([for c in aws_lb_listener_rule.this["app/0"].condition : c.host_header[0].values if length(c.host_header) > 0]) == toset(["app.example.com"]) &&
      length([for c in aws_lb_listener_rule.this["app/0"].condition : c if length(c.path_pattern) > 0]) == 0 &&
      aws_lb_listener_rule.this["app/0"].action[0].target_group_arn == aws_lb_target_group.this["next"].arn
    )
    error_message = "app 규칙이 경로 조건 없는 host 규칙 → next 가 아니다."
  }

  assert {
    condition = alltrue([
      for group in aws_lb_target_group.this : group.target_type == "ip"
    ])
    error_message = "공개 ALB 대상 그룹이 입력대로 ip 대상이 아니다."
  }
}

run "splits_by_max_path_values_per_rule" {
  command = plan

  variables {
    max_path_values_per_rule = 3
    listener_rules = {
      control = {
        priority      = 5
        target_group  = "control"
        host_headers  = ["media.example.com"]
        path_prefixes = ["/a", "/b"]
        path_patterns = ["/c/*"]
      }
    }
  }

  assert {
    condition     = toset(keys(aws_lb_listener_rule.this)) == toset(["control/0", "control/1"])
    error_message = "경로 값 5개(접두사 2 × 2 + 패턴 1)가 규칙당 3개로 두 조각이 되지 않는다."
  }

  assert {
    condition = (
      length(one([for c in aws_lb_listener_rule.this["control/0"].condition : c.path_pattern[0].values if length(c.path_pattern) > 0])) == 3 &&
      length(one([for c in aws_lb_listener_rule.this["control/1"].condition : c.path_pattern[0].values if length(c.path_pattern) > 0])) == 2 &&
      aws_lb_listener_rule.this["control/0"].priority == 5 &&
      aws_lb_listener_rule.this["control/1"].priority == 6
    )
    error_message = "조각의 경로 값 수가 3 · 2 가 아니거나 우선순위가 5 · 6 이 아니다."
  }
}

run "outputs_match_contract" {
  command = apply

  assert {
    condition = (
      output.alb_arn == aws_lb.this.arn &&
      output.dns_name == aws_lb.this.dns_name &&
      output.zone_id == aws_lb.this.zone_id
    )
    error_message = "출력 alb_arn · dns_name · zone_id 가 ALB 와 다르다."
  }

  assert {
    condition = (
      toset(keys(output.target_group_arns)) == toset(keys(var.target_groups)) &&
      alltrue([for key, arn in output.target_group_arns : arn == aws_lb_target_group.this[key].arn])
    )
    error_message = "출력 target_group_arns 의 키 → ARN 짝이 대상 그룹과 다르다."
  }
}

run "rejects_subnet_count_other_than_two" {
  command = plan

  variables {
    subnet_ids = ["subnet-0examplea"]
  }

  expect_failures = [var.subnet_ids]
}

run "rejects_alb_name_over_32_chars" {
  command = plan

  # 대상 그룹 이름 검사와 섞이지 않게 대상 그룹 · 규칙을 비운다. 33자.
  variables {
    name           = "example-name-longer-than-thirty-2"
    target_groups  = {}
    listener_rules = {}
  }

  expect_failures = [var.name]
}

# 대상 그룹 이름 = <name>-<키> 도 32자 상한이다.
run "rejects_target_group_name_over_32_chars" {
  command = plan

  variables {
    name = "example-internal-alb-name"
  }

  expect_failures = [var.target_groups]
}

run "rejects_non_http_target_group_protocol" {
  command = plan

  variables {
    target_groups = {
      live = {
        port        = 8890
        protocol    = "TCP"
        target_type = "instance"
        health_check = {
          port = "8081"
          path = "/healthz"
        }
      }
    }
    listener_rules = {}
  }

  expect_failures = [var.target_groups]
}

run "rejects_unknown_target_type" {
  command = plan

  variables {
    target_groups = {
      live = {
        port        = 8443
        protocol    = "HTTP"
        target_type = "lambda"
        health_check = {
          port = "8081"
          path = "/healthz"
        }
      }
    }
    listener_rules = {}
  }

  expect_failures = [var.target_groups]
}

run "rejects_rule_to_unknown_target_group" {
  command = plan

  variables {
    listener_rules = {
      live = {
        priority      = 10
        target_group  = "missing"
        path_patterns = ["/live/*"]
      }
    }
  }

  expect_failures = [var.listener_rules]
}

run "rejects_rule_without_conditions" {
  command = plan

  variables {
    listener_rules = {
      live = {
        priority     = 10
        target_group = "live"
      }
    }
  }

  expect_failures = [var.listener_rules]
}

# 조건 하나의 값은 3 이하다(P1).
run "rejects_max_path_values_over_three" {
  command = plan

  variables {
    max_path_values_per_rule = 4
  }

  expect_failures = [var.max_path_values_per_rule]
}

run "rejects_more_than_three_host_values" {
  command = plan

  variables {
    listener_rules = {
      live = {
        priority     = 10
        target_group = "live"
        host_headers = ["a.example.com", "b.example.com", "c.example.com", "d.example.com"]
      }
    }
  }

  expect_failures = [var.listener_rules]
}

# 규칙당 host 값 + 경로 값 합은 5 이하다(P1) — host 3 + 경로 3 은 6.
run "rejects_condition_values_over_five_per_rule" {
  command = plan

  variables {
    max_path_values_per_rule = 3
    listener_rules = {
      live = {
        priority      = 10
        target_group  = "live"
        host_headers  = ["a.example.com", "b.example.com", "c.example.com"]
        path_prefixes = ["/a", "/b"]
      }
    }
  }

  expect_failures = [var.listener_rules]
}

# 조각으로 늘어난 우선순위가 다른 규칙과 겹치면 멈춘다 — auth 는 100 · 101.
run "rejects_overlapping_split_priorities" {
  command = plan

  variables {
    listener_rules = {
      auth = {
        priority      = 100
        target_group  = "control"
        path_prefixes = ["/a", "/b"]
      }
      other = {
        priority      = 101
        target_group  = "control"
        path_patterns = ["/c/*"]
      }
    }
  }

  expect_failures = [aws_lb_listener_rule.this]
}
