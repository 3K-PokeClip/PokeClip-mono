# ECS Fargate 서비스 모듈(설계 3-3 ecs_service · 5-5 「ECS 서비스 SG」)을 mock
# provider 로 단언한다 — 계획 M8 c6: 실행 역할 비밀 읽기 Resource = 입력
# secrets 값 집합(와일드카드 없음) · egress_targets 마다 이그레스 규칙 1개 · 공인
# IP 끔 · desired 1 · Cloud Map 등록, 「O7d 선행 0」 의 실행 · 작업 역할 path =
# /pokeclip/prod/, 결정 146 의 ARM64.
# 계정 ID 는 가짜 12자리(111122223333), CIDR 은 문서용 범위(RFC 5737)다.

mock_provider "aws" {}

override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "111122223333"
  }
}

# provider 가 ARN 꼴을 검사하는 참조(태스크 정의의 역할 · 서비스의 Cloud Map)와
# 정책에 드는 로그 그룹에 서로 다른 가짜 ARN 을 준다.
override_resource {
  target = aws_iam_role.execution
  values = {
    arn = "arn:aws:iam::111122223333:role/pokeclip/prod/example-auth-execution"
  }
}

override_resource {
  target = aws_iam_role.task
  values = {
    arn = "arn:aws:iam::111122223333:role/pokeclip/prod/example-auth-task"
  }
}

override_resource {
  target = aws_service_discovery_service.this
  values = {
    arn = "arn:aws:servicediscovery:ap-northeast-2:111122223333:service/srv-0example"
  }
}

override_resource {
  target = aws_cloudwatch_log_group.this
  values = {
    arn = "arn:aws:logs:ap-northeast-2:111122223333:log-group:example-auth"
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "ap-northeast-2"
  }
}

variables {
  name_prefix            = "example"
  name                   = "auth"
  iam_path               = "/pokeclip/prod/"
  cluster_arn            = "arn:aws:ecs:ap-northeast-2:111122223333:cluster/example"
  vpc_id                 = "vpc-0example"
  subnet_ids             = ["subnet-0a", "subnet-0c"]
  image                  = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/auth@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  cpu                    = 512
  memory                 = 1024
  port                   = 8082
  health_check_grace     = 90
  log_retention_days     = 30
  discovery_namespace_id = "ns-0example"
  target_group_arns = [
    "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-auth/0123456789abcdef",
  ]
  env = {
    SPRING_PROFILES_ACTIVE   = "prod"
    FORWARD_HEADERS_STRATEGY = "native"
  }
  secrets = {
    JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-AbCdEf"
    INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-GhIjKl"
  }
  egress_targets = {
    aws_api = {
      port      = 443
      cidr_ipv4 = "0.0.0.0/0"
    }
    s3 = {
      port           = 443
      prefix_list_id = "pl-0example"
    }
    rds = {
      port              = 5432
      security_group_id = "sg-0rds"
    }
  }
  task_role_statements = [
    {
      sid       = "StreamKeySecrets"
      actions   = ["secretsmanager:CreateSecret", "secretsmanager:GetSecretValue"]
      resources = ["arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/stream-key/*"]
    },
    {
      sid       = "ProfilePhotos"
      actions   = ["s3:GetObject", "s3:PutObject"]
      resources = ["arn:aws:s3:::example-profile-photos/*"]
    },
  ]
}

run "defines_arm64_fargate_task" {
  command = plan

  assert {
    condition = (
      aws_ecs_task_definition.this.family == "example-auth" &&
      aws_ecs_task_definition.this.requires_compatibilities == toset(["FARGATE"]) &&
      aws_ecs_task_definition.this.network_mode == "awsvpc"
    )
    error_message = "태스크 정의가 <name_prefix>-<name> · FARGATE · awsvpc 가 아니다."
  }

  assert {
    condition     = aws_ecs_task_definition.this.cpu == "512" && aws_ecs_task_definition.this.memory == "1024"
    error_message = "태스크 cpu · memory 가 입력값이 아니다."
  }

  # 결정 146 — prod 는 arm64(Graviton) 단일.
  assert {
    condition = (
      aws_ecs_task_definition.this.runtime_platform[0].cpu_architecture == "ARM64" &&
      aws_ecs_task_definition.this.runtime_platform[0].operating_system_family == "LINUX"
    )
    error_message = "태스크 runtime_platform 이 ARM64 · LINUX 가 아니다."
  }
}

run "renders_container_definition_from_inputs" {
  command = apply

  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.this.container_definitions)) == 1
    error_message = "컨테이너 정의가 하나가 아니다."
  }

  assert {
    condition = (
      jsondecode(aws_ecs_task_definition.this.container_definitions)[0].name == "auth" &&
      jsondecode(aws_ecs_task_definition.this.container_definitions)[0].image == var.image &&
      jsondecode(aws_ecs_task_definition.this.container_definitions)[0].essential == true
    )
    error_message = "컨테이너 이름 · 이미지 · essential 이 입력과 다르다."
  }

  assert {
    condition = jsondecode(aws_ecs_task_definition.this.container_definitions)[0].portMappings == [
      { containerPort = 8082, protocol = "tcp" },
    ]
    error_message = "컨테이너 포트 매핑이 입력 port(TCP) 하나가 아니다."
  }

  assert {
    condition = (
      tomap({ for e in jsondecode(aws_ecs_task_definition.this.container_definitions)[0].environment : e.name => e.value }) == var.env
    )
    error_message = "컨테이너 environment 가 입력 env 와 다르다."
  }

  assert {
    condition = (
      tomap({ for s in jsondecode(aws_ecs_task_definition.this.container_definitions)[0].secrets : s.name => s.valueFrom }) == var.secrets
    )
    error_message = "컨테이너 secrets 가 입력 secrets(이름 → ARN)와 다르다."
  }

  assert {
    condition = jsondecode(aws_ecs_task_definition.this.container_definitions)[0].logConfiguration == {
      logDriver = "awslogs"
      options = {
        awslogs-group         = "example-auth"
        awslogs-region        = "ap-northeast-2"
        awslogs-stream-prefix = "auth"
      }
    }
    error_message = "컨테이너 로그 설정이 awslogs · 모듈 로그 그룹 · provider 리전이 아니다."
  }

  assert {
    condition = (
      aws_ecs_task_definition.this.execution_role_arn == aws_iam_role.execution.arn &&
      aws_ecs_task_definition.this.task_role_arn == aws_iam_role.task.arn
    )
    error_message = "태스크 정의의 실행 · 작업 역할이 모듈 역할이 아니다."
  }
}

run "creates_log_group_with_retention" {
  command = plan

  assert {
    condition     = aws_cloudwatch_log_group.this.name == "example-auth" && aws_cloudwatch_log_group.this.retention_in_days == 30
    error_message = "로그 그룹이 <name_prefix>-<name> · 입력 보존 일수가 아니다."
  }
}

run "runs_single_private_fargate_service" {
  command = apply

  assert {
    condition = (
      aws_ecs_service.this.name == "example-auth" &&
      aws_ecs_service.this.cluster == var.cluster_arn &&
      aws_ecs_service.this.task_definition == aws_ecs_task_definition.this.arn &&
      aws_ecs_service.this.launch_type == "FARGATE"
    )
    error_message = "서비스가 입력 클러스터의 FARGATE <name_prefix>-<name>(모듈 태스크 정의)가 아니다."
  }

  assert {
    condition     = aws_ecs_service.this.desired_count == 1
    error_message = "서비스 desired_count 가 1 이 아니다."
  }

  assert {
    condition = (
      aws_ecs_service.this.network_configuration[0].assign_public_ip == false &&
      aws_ecs_service.this.network_configuration[0].subnets == toset(var.subnet_ids) &&
      aws_ecs_service.this.network_configuration[0].security_groups == toset([aws_security_group.this.id])
    )
    error_message = "서비스 네트워크가 공인 IP 끔 · 입력 서브넷 · 모듈 SG 하나가 아니다."
  }

  assert {
    condition = (
      tolist([for lb in aws_ecs_service.this.load_balancer : lb.target_group_arn]) == var.target_group_arns &&
      alltrue([for lb in aws_ecs_service.this.load_balancer : lb.container_name == "auth" && lb.container_port == 8082])
    )
    error_message = "서비스 대상 그룹 연결이 입력 target_group_arns · 컨테이너 auth:8082 가 아니다."
  }

  assert {
    condition     = aws_ecs_service.this.health_check_grace_period_seconds == 90
    error_message = "헬스체크 유예가 입력 health_check_grace 가 아니다."
  }

  assert {
    condition     = aws_ecs_service.this.service_registries[0].registry_arn == aws_service_discovery_service.this.arn
    error_message = "서비스가 모듈의 Cloud Map 서비스에 등록되지 않는다."
  }
}

# 배포 비율을 주지 않으면 서비스에 넘기지 않는다(ECS 기본 최소 100 · 최대 200
# 그대로 — 이 입력이 생기기 전 서비스의 plan 이 바뀌지 않는다).
run "leaves_deployment_percents_unset_by_default" {
  command = plan

  assert {
    condition = (
      aws_ecs_service.this.deployment_minimum_healthy_percent == null &&
      aws_ecs_service.this.deployment_maximum_percent == null &&
      output.deployment_minimum_healthy_percent == null &&
      output.deployment_maximum_percent == null
    )
    error_message = "배포 비율을 주지 않았는데 서비스에 값이 있다."
  }
}

# 최소 0 · 최대 100 = 옛 태스크를 먼저 내리고 새 태스크를 띄운다(겹침 0).
run "passes_deployment_percents_to_service" {
  command = plan

  variables {
    deployment_minimum_healthy_percent = 0
    deployment_maximum_percent         = 100
  }

  assert {
    condition = (
      aws_ecs_service.this.deployment_minimum_healthy_percent == 0 &&
      aws_ecs_service.this.deployment_maximum_percent == 100
    )
    error_message = "서비스 배포 비율이 입력 최소 0 · 최대 100 이 아니다."
  }

  assert {
    condition = (
      output.deployment_minimum_healthy_percent == 0 &&
      output.deployment_maximum_percent == 100
    )
    error_message = "배포 비율 출력이 서비스 값(최소 0 · 최대 100)이 아니다."
  }
}

run "attaches_every_target_group" {
  command = plan

  variables {
    target_group_arns = [
      "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-clip/0123456789abcdef",
      "arn:aws:elasticloadbalancing:ap-northeast-2:111122223333:targetgroup/example-clip-sse/0123456789abcdef",
    ]
  }

  assert {
    condition     = toset([for lb in aws_ecs_service.this.load_balancer : lb.target_group_arn]) == toset(var.target_group_arns)
    error_message = "대상 그룹마다 load_balancer 연결이 하나씩 있지 않다."
  }
}

# 대상 그룹이 없으면 헬스체크 유예를 둘 수 없다(ECS API 거부).
run "omits_load_balancer_and_grace_without_target_groups" {
  command = plan

  variables {
    target_group_arns = []
  }

  assert {
    condition     = length(aws_ecs_service.this.load_balancer) == 0 && aws_ecs_service.this.health_check_grace_period_seconds == null
    error_message = "대상 그룹이 없는데 load_balancer · 헬스체크 유예가 있다."
  }
}

run "registers_service_in_cloud_map" {
  command = plan

  assert {
    condition = (
      aws_service_discovery_service.this.name == "auth" &&
      aws_service_discovery_service.this.dns_config[0].namespace_id == "ns-0example" &&
      aws_service_discovery_service.this.dns_config[0].routing_policy == "MULTIVALUE"
    )
    error_message = "Cloud Map 서비스가 입력 네임스페이스의 <name>(MULTIVALUE)가 아니다."
  }

  assert {
    condition = (
      length(aws_service_discovery_service.this.dns_config[0].dns_records) == 1 &&
      aws_service_discovery_service.this.dns_config[0].dns_records[0].type == "A"
    )
    error_message = "Cloud Map DNS 레코드가 A 하나가 아니다."
  }

  # 빈 블록은 provider 가 AWS 로 보내지 않아(실물 null) plan 마다 교체가 뜬다.
  assert {
    condition     = length(aws_service_discovery_service.this.health_check_custom_config) == 0
    error_message = "Cloud Map 서비스에 health_check_custom_config 블록이 있다 — 실물과 달라 영구 교체 diff 가 난다."
  }
}

# 「O7d 선행 0 · prod 역할 경로 단언」 — 이 경로가 2-3 판정의 prod 주체 식별
# 기준이다(계획 3절 이름 규약).
run "creates_roles_under_prod_path" {
  command = plan

  assert {
    condition     = aws_iam_role.execution.path == "/pokeclip/prod/" && aws_iam_role.task.path == "/pokeclip/prod/"
    error_message = "ECS 실행 · 작업 역할의 path 가 /pokeclip/prod/ 가 아니다."
  }

  assert {
    condition     = aws_iam_role.execution.name == "example-auth-execution" && aws_iam_role.task.name == "example-auth-task"
    error_message = "역할 이름이 <name_prefix>-<name>-{execution,task} 가 아니다."
  }

  assert {
    condition = alltrue([
      for role in [aws_iam_role.execution, aws_iam_role.task] :
      jsondecode(role.assume_role_policy).Statement == [{
        Effect    = "Allow"
        Action    = "sts:AssumeRole"
        Principal = { Service = "ecs-tasks.amazonaws.com" }
        Condition = { StringEquals = { "aws:SourceAccount" = "111122223333" } }
      }]
    ])
    error_message = "역할 신뢰 정책이 ecs-tasks(같은 계정 조건)만이 아니다."
  }
}

run "limits_execution_secret_read_to_input_arns" {
  command = apply

  assert {
    condition     = aws_iam_role_policy.execution.role == aws_iam_role.execution.id && aws_iam_role_policy.execution.name == "example-auth-execution"
    error_message = "실행 역할 정책이 실행 역할의 <name_prefix>-<name>-execution 이 아니다."
  }

  # Resource = 입력 secrets 값 집합 정확히(와일드카드 없음).
  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.execution.policy).Statement :
      toset(statement.Resource) == toset(values(var.secrets)) && statement.Action == ["secretsmanager:GetSecretValue"] && statement.Effect == "Allow"
      if statement.Sid == "ReadSecrets"
    ]) == true
    error_message = "비밀 읽기 문장이 GetSecretValue · Resource = 입력 secrets 값 집합이 아니다."
  }

  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.execution.policy).Statement :
      statement.Resource == ["arn:aws:ecr:ap-northeast-2:111122223333:repository/pokeclip-prod/auth"] &&
      toset(statement.Action) == toset(["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"])
      if statement.Sid == "PullImage"
    ]) == true
    error_message = "이미지 끌어오기 문장이 이미지의 ECR 저장소 하나로 좁혀지지 않았다."
  }

  # 인증 토큰 발급은 자원 수준 권한이 없어 Resource 가 * 다.
  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.execution.policy).Statement :
      statement.Resource == "*" && statement.Action == ["ecr:GetAuthorizationToken"]
      if statement.Sid == "GetAuthorizationToken"
    ]) == true
    error_message = "ECR 인증 토큰 문장이 GetAuthorizationToken 하나가 아니다."
  }

  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.execution.policy).Statement :
      statement.Resource == ["${aws_cloudwatch_log_group.this.arn}:*"] &&
      toset(statement.Action) == toset(["logs:CreateLogStream", "logs:PutLogEvents"])
      if statement.Sid == "WriteLogs"
    ]) == true
    error_message = "로그 문장이 모듈 로그 그룹의 스트림 생성 · 쓰기로 좁혀지지 않았다."
  }

  assert {
    condition = toset([
      for statement in jsondecode(aws_iam_role_policy.execution.policy).Statement : statement.Sid
    ]) == toset(["PullImage", "GetAuthorizationToken", "WriteLogs", "ReadSecrets"])
    error_message = "실행 역할 정책 문장 집합이 끌어오기 · 토큰 · 로그 · 비밀 읽기가 아니다."
  }
}

run "omits_secret_read_without_secrets" {
  command = apply

  variables {
    secrets = {}
  }

  assert {
    condition = toset([
      for statement in jsondecode(aws_iam_role_policy.execution.policy).Statement : statement.Sid
    ]) == toset(["PullImage", "GetAuthorizationToken", "WriteLogs"])
    error_message = "secrets 가 비었는데 비밀 읽기 문장이 있다."
  }
}

run "attaches_task_role_statements_from_input" {
  command = apply

  assert {
    condition     = aws_iam_role_policy.task[0].role == aws_iam_role.task.id && aws_iam_role_policy.task[0].name == "example-auth-task"
    error_message = "작업 역할 정책이 작업 역할의 <name_prefix>-<name>-task 가 아니다."
  }

  assert {
    # 튜플 · 목록 형 차이를 지우려고 JSON 문자열로 비교한다.
    condition = jsonencode(jsondecode(aws_iam_role_policy.task[0].policy).Statement) == jsonencode([
      for statement in var.task_role_statements : {
        Sid      = statement.sid
        Effect   = "Allow"
        Action   = statement.actions
        Resource = statement.resources
      }
    ])
    error_message = "작업 역할 정책 문장이 입력 task_role_statements 와 다르다."
  }
}

run "creates_task_role_without_policy_when_no_statements" {
  command = plan

  variables {
    task_role_statements = []
  }

  assert {
    condition     = length(aws_iam_role_policy.task) == 0
    error_message = "task_role_statements 가 비었는데 작업 역할 정책이 있다."
  }
}

run "creates_one_egress_rule_per_target" {
  command = apply

  assert {
    condition     = toset(keys(aws_vpc_security_group_egress_rule.this)) == toset(keys(var.egress_targets))
    error_message = "이그레스 규칙 키 집합이 egress_targets 와 다르다."
  }

  assert {
    condition = alltrue([
      for key, rule in aws_vpc_security_group_egress_rule.this :
      rule.security_group_id == aws_security_group.this.id &&
      rule.ip_protocol == "tcp" &&
      rule.from_port == var.egress_targets[key].port && rule.to_port == var.egress_targets[key].port
    ])
    error_message = "이그레스 규칙이 모듈 SG 의 TCP <port> 가 아니다."
  }

  assert {
    condition = (
      aws_vpc_security_group_egress_rule.this["aws_api"].cidr_ipv4 == "0.0.0.0/0" &&
      aws_vpc_security_group_egress_rule.this["s3"].prefix_list_id == "pl-0example" &&
      aws_vpc_security_group_egress_rule.this["rds"].referenced_security_group_id == "sg-0rds"
    )
    error_message = "이그레스 규칙의 상대가 입력(CIDR · 접두사 목록 · SG)과 다르다."
  }

  assert {
    condition = (
      aws_vpc_security_group_egress_rule.this["aws_api"].prefix_list_id == null &&
      aws_vpc_security_group_egress_rule.this["aws_api"].referenced_security_group_id == null &&
      aws_vpc_security_group_egress_rule.this["rds"].cidr_ipv4 == null
    )
    error_message = "이그레스 규칙에 입력에 없는 상대가 함께 들었다."
  }

  assert {
    condition     = aws_security_group.this.name == "example-auth" && aws_security_group.this.vpc_id == "vpc-0example"
    error_message = "서비스 SG 가 입력 VPC 의 <name_prefix>-<name> 가 아니다."
  }
}

run "outputs_match_contract" {
  command = apply

  assert {
    condition = (
      output.service_name == aws_ecs_service.this.name &&
      output.task_role_arn == aws_iam_role.task.arn &&
      output.execution_role_arn == aws_iam_role.execution.arn &&
      output.sg_id == aws_security_group.this.id &&
      output.discovery_name == "auth"
    )
    error_message = "출력이 서비스 · 역할 · SG · Cloud Map 이름과 다르다."
  }
}

# 접두 와일드카드(…/stream-key/*)는 받고 맨 * 는 거부한다.
run "rejects_bare_wildcard_task_resource" {
  command = plan

  variables {
    task_role_statements = [
      {
        sid       = "Everything"
        actions   = ["s3:GetObject"]
        resources = ["*"]
      },
    ]
  }

  expect_failures = [var.task_role_statements]
}

run "rejects_invalid_task_statement_sid" {
  command = plan

  variables {
    task_role_statements = [
      {
        sid       = "profile-photos"
        actions   = ["s3:GetObject"]
        resources = ["arn:aws:s3:::example-profile-photos/*"]
      },
    ]
  }

  expect_failures = [var.task_role_statements]
}

run "rejects_empty_task_statement_actions" {
  command = plan

  variables {
    task_role_statements = [
      {
        sid       = "Nothing"
        actions   = []
        resources = ["arn:aws:s3:::example-profile-photos/*"]
      },
    ]
  }

  expect_failures = [var.task_role_statements]
}

run "rejects_wildcard_secret_arn" {
  command = plan

  variables {
    secrets = {
      JWT_SECRET = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/*"
    }
  }

  expect_failures = [var.secrets]
}

run "rejects_secret_value_that_is_not_an_arn" {
  command = plan

  variables {
    secrets = {
      JWT_SECRET = "pokeclip/prod/jwt"
    }
  }

  expect_failures = [var.secrets]
}

run "rejects_secret_name_also_in_env" {
  command = plan

  variables {
    env = {
      JWT_SECRET = "plain"
    }
  }

  expect_failures = [var.secrets]
}

run "rejects_egress_target_with_two_destinations" {
  command = plan

  variables {
    egress_targets = {
      both = {
        port           = 443
        cidr_ipv4      = "0.0.0.0/0"
        prefix_list_id = "pl-0example"
      }
    }
  }

  expect_failures = [var.egress_targets]
}

run "rejects_egress_target_without_destination" {
  command = plan

  variables {
    egress_targets = {
      none = {
        port = 443
      }
    }
  }

  expect_failures = [var.egress_targets]
}

run "rejects_image_outside_ecr" {
  command = plan

  variables {
    image = "docker.io/library/nginx:latest"
  }

  expect_failures = [var.image]
}

run "rejects_iam_path_without_slashes" {
  command = plan

  variables {
    iam_path = "pokeclip/prod"
  }

  expect_failures = [var.iam_path]
}

run "rejects_empty_subnet_ids" {
  command = plan

  variables {
    subnet_ids = []
  }

  expect_failures = [var.subnet_ids]
}

run "rejects_unsupported_fargate_cpu" {
  command = plan

  variables {
    cpu = 300
  }

  expect_failures = [var.cpu]
}

run "rejects_unsupported_log_retention" {
  command = plan

  variables {
    log_retention_days = 0
  }

  expect_failures = [var.log_retention_days]
}

run "rejects_deployment_minimum_above_100" {
  command = plan

  variables {
    deployment_minimum_healthy_percent = 101
  }

  expect_failures = [var.deployment_minimum_healthy_percent]
}

run "rejects_negative_deployment_minimum" {
  command = plan

  variables {
    deployment_minimum_healthy_percent = -1
  }

  expect_failures = [var.deployment_minimum_healthy_percent]
}

run "rejects_deployment_maximum_below_100" {
  command = plan

  variables {
    deployment_maximum_percent = 99
  }

  expect_failures = [var.deployment_maximum_percent]
}

run "rejects_deployment_maximum_above_200" {
  command = plan

  variables {
    deployment_maximum_percent = 201
  }

  expect_failures = [var.deployment_maximum_percent]
}

run "rejects_fractional_deployment_percent" {
  command = plan

  variables {
    deployment_minimum_healthy_percent = 50.5
  }

  expect_failures = [var.deployment_minimum_healthy_percent]
}
