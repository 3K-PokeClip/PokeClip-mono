# ECS Fargate 서비스 하나(설계 3-3 ecs_service · 5-5 · 결정 146 arm64). 태스크
# 정의 · 서비스 · 서비스 SG(이그레스만) · Cloud Map 등록 · 로그 그룹 · 실행 ·
# 작업 역할을 만든다. 인그레스 규칙은 상대 SG 를 아는 호출자(platform 루트)가
# 만든다. 비밀 값은 입력에 없다 — ARN 만 받아 ECS 가 기동 때 읽는다.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  full_name = "${var.name_prefix}-${var.name}"

  # <계정>.dkr.ecr.<리전>.amazonaws.com/<저장소>(:<태그> | @sha256:<다이제스트>)
  ecr_image_pattern = "^([0-9]{12})\\.dkr\\.ecr\\.([a-z0-9-]+)\\.amazonaws\\.com/([a-z0-9][a-z0-9._/-]*)(?::[A-Za-z0-9_][A-Za-z0-9._-]*|@sha256:[a-f0-9]{64})$"
  image_parts       = regex(local.ecr_image_pattern, var.image)
  repository_arn    = "arn:aws:ecr:${local.image_parts[1]}:${local.image_parts[0]}:repository/${local.image_parts[2]}"

  secret_arns = sort(distinct(values(var.secrets)))

  # ECS 가 이 계정의 태스크로만 역할을 맡게 한다(혼동된 대리인 방지).
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } }
    }]
  })
}

resource "aws_cloudwatch_log_group" "this" {
  name              = local.full_name
  retention_in_days = var.log_retention_days
}

resource "aws_iam_role" "execution" {
  name               = "${local.full_name}-execution"
  path               = var.iam_path
  assume_role_policy = local.assume_role_policy
}

# 실행 역할 = ECS 에이전트가 이미지를 끌어오고 로그 스트림을 만들고 비밀을
# 읽는 권한. 비밀 읽기 Resource 는 입력 secrets 의 ARN 집합 그대로다.
resource "aws_iam_role_policy" "execution" {
  name = "${local.full_name}-execution"
  role = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [
        {
          Sid      = "PullImage"
          Effect   = "Allow"
          Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
          Resource = [local.repository_arn]
        },
        {
          # 인증 토큰 발급은 자원 수준 권한이 없다.
          Sid      = "GetAuthorizationToken"
          Effect   = "Allow"
          Action   = ["ecr:GetAuthorizationToken"]
          Resource = "*"
        },
        {
          Sid      = "WriteLogs"
          Effect   = "Allow"
          Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
          Resource = ["${aws_cloudwatch_log_group.this.arn}:*"]
        },
      ],
      [
        for arns in [local.secret_arns] : {
          Sid      = "ReadSecrets"
          Effect   = "Allow"
          Action   = ["secretsmanager:GetSecretValue"]
          Resource = arns
        } if length(arns) > 0
      ],
    )
  })
}

# 작업 역할 = 앱 코드가 부르는 AWS 동작. 문장은 호출자가 코드에서 읽어 정한다.
resource "aws_iam_role" "task" {
  name               = "${local.full_name}-task"
  path               = var.iam_path
  assume_role_policy = local.assume_role_policy
}

resource "aws_iam_role_policy" "task" {
  count = length(var.task_role_statements) > 0 ? 1 : 0

  name = "${local.full_name}-task"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      for statement in var.task_role_statements : {
        Sid      = statement.sid
        Effect   = "Allow"
        Action   = statement.actions
        Resource = statement.resources
      }
    ]
  })
}

resource "aws_security_group" "this" {
  name        = local.full_name
  description = "ECS service ${var.name}"
  vpc_id      = var.vpc_id
}

# 상대마다 규칙 하나(키 = 호출자가 붙인 이름). Terraform 이 만든 SG 에는 기본
# 이그레스가 없어 이 규칙들만 나간다.
resource "aws_vpc_security_group_egress_rule" "this" {
  for_each = var.egress_targets

  security_group_id            = aws_security_group.this.id
  description                  = each.value.description
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  cidr_ipv4                    = each.value.cidr_ipv4
  prefix_list_id               = each.value.prefix_list_id
  referenced_security_group_id = each.value.security_group_id
}

resource "aws_service_discovery_service" "this" {
  name = var.name

  dns_config {
    namespace_id   = var.discovery_namespace_id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "A"
      ttl  = 10
    }
  }

  # 헬스는 ECS 가 알린다 — 멈추거나 unhealthy 인 태스크를 이름에서 뺀다.
  health_check_custom_config {}
}

resource "aws_ecs_task_definition" "this" {
  family                   = local.full_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    cpu_architecture        = "ARM64"
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([{
    name         = var.name
    image        = var.image
    essential    = true
    portMappings = [{ containerPort = var.port, protocol = "tcp" }]
    environment  = [for key, value in var.env : { name = key, value = value }]
    secrets      = [for key, arn in var.secrets : { name = key, valueFrom = arn }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = var.name
      }
    }
  }])
}

resource "aws_ecs_service" "this" {
  name            = local.full_name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  launch_type     = "FARGATE"
  desired_count   = 1

  # null 이면 넘기지 않는다 — ECS 기본(최소 100 · 최대 200)이 그대로 산다.
  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent

  # ECS API 는 대상 그룹 없이 유예를 받지 않는다.
  health_check_grace_period_seconds = length(var.target_group_arns) > 0 ? var.health_check_grace : null

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.this.id]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = var.target_group_arns

    content {
      target_group_arn = load_balancer.value
      container_name   = var.name
      container_port   = var.port
    }
  }

  service_registries {
    registry_arn = aws_service_discovery_service.this.arn
  }

  # 첫 태스크가 이미지 · 비밀을 읽기 전에 실행 역할 정책이 있게 한다.
  depends_on = [aws_iam_role_policy.execution]
}
