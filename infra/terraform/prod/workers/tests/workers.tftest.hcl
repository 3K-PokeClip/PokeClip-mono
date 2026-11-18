# prod/workers 루트(POK-408 — 설계 2-3 · 3절 · 7-3)를 mock provider 로
# 단언한다 — 이미지 다이제스트 고정 · 크기 · prod 경로, env · 비밀 · 이그레스 ·
# 작업 역할 문장이 계약(CONTRACT-runtime.md 「일꾼 작업 역할 대상」)과 정확히
# 같은지, 상대 SG 인그레스 셋, 런타임 계약 검사(terraform_data.runtime_contract)
# 가 어긋난 앞 루트 출력에서 plan 을 멈추는지. 자동 조절(scaling.tf — POK-605)의
# 확장 대상 · 정책 · 경보는 파일 끝 run 들이 본다.
#
# 앞 루트 상태는 override_data 로 흉내 낸다. 계정 ID 는 가짜 12자리
# (111122223333)이고, 런타임 출력은 이 루트가 읽는 키만 둔다. 루트 시험은 하위
# 모듈 안의 자원을 볼 수 없어 모듈에 넘기는 지역값 · 루트 자원 · 모듈 출력을
# 단언한다. 모듈 안 동작은 modules/ecs_service 시험이 맡는다.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
    }
  }

  mock_data "aws_region" {
    defaults = {
      region = "ap-northeast-2"
    }
  }

  # apply 로 도는 run 에서 provider 가 ARN 꼴을 검사하는 참조에 쓸 값.
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::111122223333:role/pokeclip/prod/example"
    }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = {
      arn = "arn:aws:logs:ap-northeast-2:111122223333:log-group:example"
    }
  }

  mock_resource "aws_service_discovery_service" {
    defaults = {
      arn = "arn:aws:servicediscovery:ap-northeast-2:111122223333:service/srv-0example"
    }
  }

  mock_resource "aws_ecs_task_definition" {
    defaults = {
      arn = "arn:aws:ecs:ap-northeast-2:111122223333:task-definition/example:1"
    }
  }

  # 경보 동작(alarm_actions)이 ARN 꼴을 검사한다. 정책마다 다른 값은 배선 run
  # 이 override_resource 로 준다.
  mock_resource "aws_appautoscaling_policy" {
    defaults = {
      arn = "arn:aws:autoscaling:ap-northeast-2:111122223333:scalingPolicy:example:resource/ecs/service/pokeclip-prod/example:policyName/example"
    }
  }
}

override_data {
  target = data.aws_service_discovery_dns_namespace.internal
  values = {
    id = "ns-0example"
  }
}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id                         = "vpc-0example"
      private_subnet_ids             = ["subnet-0priva", "subnet-0privc"]
      s3_prefix_list_id              = "pl-0example"
      interface_endpoint_sg_id       = "sg-0123456789abcdef0"
      interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
      interface_endpoint_private_dns = true
    }
  }
}

override_data {
  target = data.terraform_remote_state.platform
  values = {
    outputs = {
      ecs_cluster_arn        = "arn:aws:ecs:ap-northeast-2:111122223333:cluster/pokeclip-prod"
      discovery_namespace_id = "ns-0example"
      service_sg_ids = {
        next = "sg-0next"
        auth = "sg-0auth"
        clip = "sg-0clip"
      }
      service_ports = {
        next = 23000
        auth = 28082
        clip = 28081
      }
    }
  }
}

override_data {
  target = data.terraform_remote_state.registry
  values = {
    outputs = {
      repo_urls = {
        render = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/render"
        upload = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/upload"
      }
    }
  }
}

override_data {
  target = data.terraform_remote_state.runtime
  values = {
    outputs = {
      app_env = {
        render = {
          RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
          THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
          RENDER_VISIBILITY_TIMEOUT    = "600s"
          THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
        }
        upload = {
          UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
          UPLOAD_VISIBILITY_TIMEOUT = "900s"
        }
      }
      app_secret_arns = {
        clip = {
          JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
          INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
        }
        render = {
          INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
        }
        upload = {
          INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
        }
      }
      app_task_resource_arns = {
        render = {
          render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
          thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
          segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
          clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
        }
        upload = {
          upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
          clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
        }
      }
    }
  }
}

variables {
  image_digests = {
    render = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    upload = "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
  }
}

run "workers_pin_digests_sizes_and_prod_path" {
  command = plan

  assert {
    condition = local.worker_images == {
      render = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/render@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
      upload = "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com/pokeclip-prod/upload@sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
    error_message = "일꾼 이미지가 registry repo_urls 의 같은 키 저장소 @ 입력 다이제스트가 아니다."
  }

  assert {
    condition = (
      var.workers.render.cpu == 2048 && var.workers.render.memory == 4096 && var.workers.render.ephemeral_storage_gib == 40 &&
      var.workers.upload.cpu == 512 && var.workers.upload.memory == 1024 && var.workers.upload.ephemeral_storage_gib == null
    )
    error_message = "일꾼 크기 기본값이 kty 확정(render 2 vCPU · 4GiB · 40GiB, upload 0.5 vCPU · 1GiB · 기본 디스크)과 다르다."
  }

  assert {
    condition     = local.iam_path == "/pokeclip/prod/" && local.log_retention_days == 30
    error_message = "역할 경로가 /pokeclip/prod/ 가 아니거나 로그 보존이 platform 과 같은 30일이 아니다."
  }

  assert {
    condition     = module.render.service_name == "pokeclip-prod-render" && module.upload.service_name == "pokeclip-prod-upload"
    error_message = "서비스 이름이 pokeclip-prod-<키> 가 아니다(토큰 회전 런북이 이 이름으로 찾는다)."
  }
}

run "render_env_is_runtime_four_plus_clip_url" {
  command = plan

  assert {
    condition = local.worker_env.render == {
      RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
      THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
      RENDER_VISIBILITY_TIMEOUT    = "600s"
      THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
      CLIP_BASE_URL                = "http://clip.pokeclip.internal:28081"
      AWS_RETRY_MODE               = "legacy"
      AWS_MAX_ATTEMPTS             = "4"
    }
    error_message = "render env 가 런타임 app_env.render 넷 + CLIP_BASE_URL(clip Cloud Map 이름 · platform service_ports.clip) + SDK 재시도 고정 둘(AWS_RETRY_MODE=legacy · AWS_MAX_ATTEMPTS=4)과 다르다."
  }
}

run "upload_env_is_runtime_two_plus_clip_and_auth_url" {
  command = plan

  assert {
    condition = local.worker_env.upload == {
      UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
      UPLOAD_VISIBILITY_TIMEOUT = "900s"
      CLIP_BASE_URL             = "http://clip.pokeclip.internal:28081"
      AUTH_BASE_URL             = "http://auth.pokeclip.internal:28082"
      AWS_RETRY_MODE            = "legacy"
      AWS_MAX_ATTEMPTS          = "4"
    }
    error_message = "upload env 가 런타임 app_env.upload 둘 + CLIP_BASE_URL · AUTH_BASE_URL + SDK 재시도 고정 둘(AWS_RETRY_MODE=legacy · AWS_MAX_ATTEMPTS=4)과 다르다."
  }
}

run "workers_read_only_internal_token" {
  command = plan

  assert {
    condition = local.worker_secrets == {
      render = { INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj" }
      upload = { INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj" }
    }
    error_message = "일꾼 비밀이 clip 과 같은 INTERNAL_API_TOKEN 그릇 하나가 아니다."
  }
}

run "task_role_statements_match_contract_table" {
  command = plan

  assert {
    condition = local.task_role_statements.render == [
      {
        sid       = "JobQueues"
        actions   = ["sqs:ChangeMessageVisibility", "sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = ["arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render", "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"]
      },
      {
        sid       = "SourceObjects"
        actions   = ["s3:GetObject"]
        resources = ["arn:aws:s3:::pokeclip-prod-rewind-example/*", "arn:aws:s3:::pokeclip-prod-clips-example/*"]
      },
      {
        sid       = "SourceList"
        actions   = ["s3:ListBucket"]
        resources = ["arn:aws:s3:::pokeclip-prod-rewind-example", "arn:aws:s3:::pokeclip-prod-clips-example"]
      },
      {
        sid       = "ClipObjects"
        actions   = ["s3:DeleteObject", "s3:PutObject"]
        resources = ["arn:aws:s3:::pokeclip-prod-clips-example/*"]
      },
    ]
    error_message = "render 작업 역할 문장이 계약 표(CONTRACT-runtime.md 「일꾼 작업 역할 대상」)의 넷과 다르다."
  }

  assert {
    condition = local.task_role_statements.upload == [
      {
        sid       = "UploadQueue"
        actions   = ["sqs:ChangeMessageVisibility", "sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = ["arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"]
      },
      {
        sid       = "ClipRead"
        actions   = ["s3:GetObject"]
        resources = ["arn:aws:s3:::pokeclip-prod-clips-example/*"]
      },
    ]
    error_message = "upload 작업 역할 문장이 계약 표의 둘과 다르다."
  }
}

run "egress_targets_per_worker" {
  command = plan

  assert {
    condition = local.egress_targets.render == {
      aws_endpoints = { port = 443, security_group_id = "sg-0123456789abcdef0", description = "AWS APIs via VPC interface endpoints" }
      s3            = { port = 443, prefix_list_id = "pl-0example", description = "S3 gateway endpoint" }
      clip          = { port = 28081, security_group_id = "sg-0clip", description = "render to clip" }
    }
    error_message = "render 이그레스가 aws_endpoints(network 의 엔드포인트 SG) · s3 · clip 셋이 아니다 — 0.0.0.0/0 이 남으면 ffmpeg 가 아무 호스트로나 나간다."
  }

  assert {
    condition = local.egress_targets.upload == {
      aws_api = { port = 443, cidr_ipv4 = "0.0.0.0/0", description = "AWS APIs and YouTube via NAT" }
      s3      = { port = 443, prefix_list_id = "pl-0example", description = "S3 gateway endpoint" }
      clip    = { port = 28081, security_group_id = "sg-0clip", description = "upload to clip" }
      auth    = { port = 28082, security_group_id = "sg-0auth", description = "upload to auth resolve" }
    }
    error_message = "upload 이그레스가 aws_api · s3 · clip · auth 넷이 아니다."
  }
}

# 위 run 들은 지역값만 본다. 모듈 블록이 다른 일꾼의 값을 받으면(예: upload 에
# render 작업 역할) 지역값은 맞아도 서비스가 틀린다 — 모듈이 실제로 만든 태스크
# 정의 · 작업 역할 정책 · 이그레스를 그 일꾼의 지역값과 맞춘다. 컨테이너 정의의
# 로그 리전이 apply 때 정해져 apply 로 돈다.
run "modules_receive_their_own_worker_inputs" {
  command = apply

  assert {
    condition = alltrue([
      for key, task in { render = module.render.task_definition, upload = module.upload.task_definition } :
      task.cpu == tostring(var.workers[key].cpu) &&
      task.memory == tostring(var.workers[key].memory) &&
      task.ephemeral_storage_gib == var.workers[key].ephemeral_storage_gib
    ])
    error_message = "모듈 태스크 정의의 cpu · 메모리 · 임시 디스크가 같은 일꾼의 workers 값이 아니다."
  }

  assert {
    condition = alltrue([
      for key, task in { render = module.render.task_definition, upload = module.upload.task_definition } :
      jsonencode(jsondecode(task.container_definitions)[0].image) == jsonencode(local.worker_images[key]) &&
      jsonencode(jsondecode(task.container_definitions)[0].environment) == jsonencode([
        for name, value in local.worker_env[key] : { name = name, value = value }
      ]) &&
      jsonencode(jsondecode(task.container_definitions)[0].secrets) == jsonencode([
        for name, arn in local.worker_secrets[key] : { name = name, valueFrom = arn }
      ])
    ])
    error_message = "모듈 컨테이너 정의의 이미지 · env · 비밀이 같은 일꾼의 지역값이 아니다."
  }

  assert {
    condition = alltrue([
      for key, policy in { render = module.render.task_role_policy, upload = module.upload.task_role_policy } :
      jsonencode(jsondecode(policy).Statement) == jsonencode([
        for statement in local.task_role_statements[key] : {
          Sid      = statement.sid
          Effect   = "Allow"
          Action   = statement.actions
          Resource = statement.resources
        }
      ])
    ])
    error_message = "모듈 작업 역할 정책이 같은 일꾼의 task_role_statements 가 아니다."
  }

  assert {
    condition = alltrue([
      for key, rules in { render = module.render.egress_rules, upload = module.upload.egress_rules } :
      jsonencode(rules) == jsonencode({
        for name, target in local.egress_targets[key] : name => {
          port              = target.port
          cidr_ipv4         = try(target.cidr_ipv4, null)
          prefix_list_id    = try(target.prefix_list_id, null)
          security_group_id = try(target.security_group_id, null)
          description       = target.description
        }
      })
    ])
    error_message = "모듈 SG 이그레스 규칙이 같은 일꾼의 egress_targets 가 아니다."
  }
}

# 수신 감시의 실패 줄 간격 셈(README 「수신 불능」 — LEGACY 4회 · 최악 약
# 134초)의 입력이 태스크 정의에 실리는지 모듈이 만든 컨테이너 정의에서 본다.
# 위 run 은 지역값과 컨테이너 정의가 같은지만 봐서, 지역값에서 빠지면 함께
# 빠진다. 컨테이너 정의의 로그 리전이 apply 때 정해져 apply 로 돈다.
run "workers_pin_sdk_retry_mode_and_attempts" {
  command = apply

  assert {
    condition = alltrue([
      for task in [module.render.task_definition, module.upload.task_definition] : (
        lookup({ for pair in jsondecode(task.container_definitions)[0].environment : pair.name => pair.value }, "AWS_RETRY_MODE", "") == "legacy" &&
        lookup({ for pair in jsondecode(task.container_definitions)[0].environment : pair.name => pair.value }, "AWS_MAX_ATTEMPTS", "") == "4"
      )
    ])
    error_message = "render · upload 태스크 정의 env 에 AWS_RETRY_MODE=legacy · AWS_MAX_ATTEMPTS=4 가 없다 — SDK 판 · 기본 재시도가 바뀌면 수신 감시의 틈 180초 셈이 깨진다."
  }
}

# SG id 가 apply 때 정해져 apply 로 돈다.
run "ingress_rules_pair_workers_to_clip_and_auth" {
  command = apply

  assert {
    condition = {
      for key, rule in aws_vpc_security_group_ingress_rule.clip_from_worker :
      key => [rule.security_group_id, rule.ip_protocol, rule.from_port, rule.to_port, rule.referenced_security_group_id]
      } == {
      render = ["sg-0clip", "tcp", 28081, 28081, module.render.sg_id]
      upload = ["sg-0clip", "tcp", 28081, 28081, module.upload.sg_id]
    }
    error_message = "clip 인그레스가 render · upload SG 에서 clip 포트로 들어오는 둘이 아니다."
  }

  assert {
    condition = [
      aws_vpc_security_group_ingress_rule.auth_from_upload.security_group_id,
      aws_vpc_security_group_ingress_rule.auth_from_upload.ip_protocol,
      aws_vpc_security_group_ingress_rule.auth_from_upload.from_port,
      aws_vpc_security_group_ingress_rule.auth_from_upload.to_port,
      aws_vpc_security_group_ingress_rule.auth_from_upload.referenced_security_group_id,
    ] == ["sg-0auth", "tcp", 28082, 28082, module.upload.sg_id]
    error_message = "auth 인그레스가 upload SG 에서 auth 포트로 들어오는 하나가 아니다."
  }
}

# 런타임이 일꾼이 모르는 이름을 더하면 평문 env 로 실리기 전에 멈춘다(검사 1).
run "runtime_contract_rejects_unknown_env_name" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
            JAVA_TOOL_OPTIONS            = "-Xmx1g"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 숨김 시간이 빠지면 일꾼이 큐와 다른 기본값을 쓴다(검사 1).
run "runtime_contract_rejects_missing_env_name" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 숨김 시간은 Spring Duration 의 초 단위 꼴(600s)이어야 한다(검사 2).
run "runtime_contract_rejects_visibility_without_seconds_unit" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 받는 큐(URL)와 작업 역할이 허락한 큐(ARN)가 다르면 받기가 권한 거부다(검사 2).
run "runtime_contract_rejects_queue_url_arn_mismatch" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 일꾼이 clip 과 다른 토큰 그릇을 읽으면 사진 보고가 401 이고 메시지가 그 자리에서
# 지워진다(설계 7-1 의 ④ · 검사 3).
run "runtime_contract_rejects_token_not_shared_with_clip" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/worker-token-KlMnOp"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 일꾼 실행 역할은 INTERNAL_API_TOKEN 그릇 하나만 읽는다(검사 3).
run "runtime_contract_rejects_two_worker_secret_keys" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 작업 역할 대상 키는 계약 표의 것뿐이다(검사 4).
run "runtime_contract_rejects_unknown_task_resource_key" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
            render_dlq_arn      = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render-dlq"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 와일드카드가 든 ARN 이 정책 Resource 에 그대로 들어가지 않게 한다(검사 4).
run "runtime_contract_rejects_wildcard_resource_arn" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-*"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 일 큐는 표준 큐다(prod/runtime job_queues.tf). URL 과 ARN 이 같아도 FIFO 면 멈춘다
# (검사 4).
run "runtime_contract_rejects_fifo_queue_arn" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-render.fifo"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-thumbnail"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/pokeclip-prod-jobs-upload"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            JWT_SECRET         = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/jwt-secret-StUvWx"
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-render.fifo"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-thumbnail"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:pokeclip-prod-jobs-upload"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 상대 SG 자리에 SG 가 아닌 값이 오면 이그레스 · 인그레스가 엉뚱한 곳을 가리킨다
# (검사 5).
run "runtime_contract_rejects_non_sg_platform_sg" {
  command = plan

  override_data {
    target = data.terraform_remote_state.platform
    values = {
      outputs = {
        ecs_cluster_arn        = "arn:aws:ecs:ap-northeast-2:111122223333:cluster/pokeclip-prod"
        discovery_namespace_id = "ns-0example"
        service_sg_ids = {
          next = "sg-0next"
          auth = "sg-0auth"
          clip = "vpc-0example"
        }
        service_ports = {
          next = 23000
          auth = 28082
          clip = 28081
        }
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 주소의 상수 도메인(pokeclip.internal)이 platform 네임스페이스와 다르면 일꾼이
# 보고할 곳을 찾지 못한다(검사 6).
run "runtime_contract_rejects_namespace_id_mismatch" {
  command = plan

  override_data {
    target = data.aws_service_discovery_dns_namespace.internal
    values = {
      id = "ns-0other"
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 모듈이 실제로 만든 render 이그레스에 인터넷(0.0.0.0/0)이 없고 AWS API 는
# network 의 엔드포인트 SG 443 으로만 나간다(설계 3-3). upload 는 YouTube 때문에
# NAT 규칙을 그대로 갖는다. SG id 가 apply 때 정해져 apply 로 돈다.
run "render_egress_rules_reach_endpoints_not_internet" {
  command = apply

  assert {
    condition = (
      length([for rule in values(module.render.egress_rules) : rule if rule.cidr_ipv4 != null]) == 0 &&
      module.render.egress_rules.aws_endpoints.security_group_id == "sg-0123456789abcdef0" &&
      module.render.egress_rules.aws_endpoints.port == 443
    )
    error_message = "render 모듈 이그레스에 CIDR 규칙이 있거나 AWS API 규칙이 엔드포인트 SG 443 이 아니다."
  }

  assert {
    condition     = module.upload.egress_rules.aws_api.cidr_ipv4 == "0.0.0.0/0"
    error_message = "upload 이그레스가 NAT(0.0.0.0/0:443)를 잃었다 — YouTube 업로드가 막힌다."
  }
}

# 엔드포인트 SG 자리에 SG 가 아닌 값이 오면 render 이그레스가 엉뚱한 곳을
# 가리킨다(계약 검사 7).
run "endpoint_contract_rejects_non_sg_endpoint_sg" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id                         = "vpc-0example"
        private_subnet_ids             = ["subnet-0priva", "subnet-0privc"]
        s3_prefix_list_id              = "pl-0example"
        interface_endpoint_sg_id       = "vpc-0example"
        interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
        interface_endpoint_private_dns = true
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 엔드포인트가 없는 network(목록이 빔)는 SG 출력이 null 이다(계약 검사 7).
run "endpoint_contract_rejects_null_endpoint_sg" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id                         = "vpc-0example"
        private_subnet_ids             = ["subnet-0priva", "subnet-0privc"]
        s3_prefix_list_id              = "pl-0example"
        interface_endpoint_sg_id       = null
        interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
        interface_endpoint_private_dns = true
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# PR-N 앞의 network 상태에는 출력 키가 없다 — 속성 오류가 아니라 계약 검사
# 문구로 멈춘다(계약 검사 7).
run "endpoint_contract_rejects_missing_endpoint_sg_key" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id                         = "vpc-0example"
        private_subnet_ids             = ["subnet-0priva", "subnet-0privc"]
        s3_prefix_list_id              = "pl-0example"
        interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
        interface_endpoint_private_dns = true
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 다섯 중 하나(sqs)라도 빠지면 render 가 수신하지 못하는데 plan 은 조용히
# 통과한다 — 그 전에 멈춘다(계약 검사 8).
run "endpoint_contract_rejects_missing_service" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id                         = "vpc-0example"
        private_subnet_ids             = ["subnet-0priva", "subnet-0privc"]
        s3_prefix_list_id              = "pl-0example"
        interface_endpoint_sg_id       = "sg-0123456789abcdef0"
        interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager"]
        interface_endpoint_private_dns = true
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# private DNS 가 꺼지면 SDK 가 공용 이름으로 나가 SG 에 막힌다(계약 검사 8).
run "endpoint_contract_rejects_private_dns_off" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id                         = "vpc-0example"
        private_subnet_ids             = ["subnet-0priva", "subnet-0privc"]
        s3_prefix_list_id              = "pl-0example"
        interface_endpoint_sg_id       = "sg-0123456789abcdef0"
        interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
        interface_endpoint_private_dns = false
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 서비스 · DNS 출력 키가 없으면 속성 오류가 아니라 계약 검사 문구로
# 멈춘다(계약 검사 8).
run "endpoint_contract_rejects_missing_service_and_dns_keys" {
  command = plan

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id                   = "vpc-0example"
        private_subnet_ids       = ["subnet-0priva", "subnet-0privc"]
        s3_prefix_list_id        = "pl-0example"
        interface_endpoint_sg_id = "sg-0123456789abcdef0"
      }
    }
  }

  expect_failures = [terraform_data.runtime_contract]
}

# 되돌리기 2단계의 첫걸음(false)은 엔드포인트 출력이 없어도 plan 이 돈다 — 계약
# 검사 7 · 8 은 true 일 때만 본다. render 이그레스는 엔드포인트 앞의 값이다.
run "rollback_false_restores_nat_without_endpoint_outputs" {
  command = plan

  variables {
    render_egress_via_endpoints = false
  }

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id             = "vpc-0example"
        private_subnet_ids = ["subnet-0priva", "subnet-0privc"]
        s3_prefix_list_id  = "pl-0example"
      }
    }
  }

  assert {
    condition = local.egress_targets.render == {
      aws_api = { port = 443, cidr_ipv4 = "0.0.0.0/0", description = "AWS APIs via NAT" }
      s3      = { port = 443, prefix_list_id = "pl-0example", description = "S3 gateway endpoint" }
      clip    = { port = 28081, security_group_id = "sg-0clip", description = "render to clip" }
    }
    error_message = "되돌리기(false)의 render 이그레스가 엔드포인트 앞의 값(aws_api · s3 · clip)이 아니다."
  }
}

# DNS 를 끈 동안(되돌리기 1단계)에도 false 면 workers 를 plan 할 수 있다.
run "rollback_false_passes_with_private_dns_off" {
  command = plan

  variables {
    render_egress_via_endpoints = false
  }

  override_data {
    target = data.terraform_remote_state.network
    values = {
      outputs = {
        vpc_id                         = "vpc-0example"
        private_subnet_ids             = ["subnet-0priva", "subnet-0privc"]
        s3_prefix_list_id              = "pl-0example"
        interface_endpoint_sg_id       = "sg-0123456789abcdef0"
        interface_endpoint_services    = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sqs"]
        interface_endpoint_private_dns = false
      }
    }
  }
}

run "rejects_tag_instead_of_digest" {
  command = plan

  variables {
    image_digests = {
      render = "209da12a"
      upload = "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
  }

  expect_failures = [var.image_digests]
}

run "rejects_unknown_worker_key" {
  command = plan

  variables {
    workers = {
      render    = { cpu = 2048, memory = 4096, ephemeral_storage_gib = 40 }
      upload    = { cpu = 512, memory = 1024 }
      thumbnail = { cpu = 512, memory = 1024 }
    }
  }

  expect_failures = [var.workers]
}

# 자동 조절(scaling.tf — POK-605). 일꾼마다 SQS 깨우기 · 재우기 경보 둘이 확장
# 대상 하나의 정책 둘을 부른다. 아래 run 들은 계획 「쟁점 2」 값 표를 그대로
# 단언한다. 경보 식의 큐는 일꾼마다 자기가 받는 큐뿐이어야 한다 — 남의 큐가
# 섞이면 그 일꾼이 남의 일로 깨어 30분 넘게 돈다.

run "scaling_targets_are_zero_to_one_per_worker_service" {
  command = plan

  assert {
    condition = {
      for key, target in aws_appautoscaling_target.worker :
      key => [target.service_namespace, target.scalable_dimension, target.resource_id, target.min_capacity, target.max_capacity]
      } == {
      render = ["ecs", "ecs:service:DesiredCount", "service/pokeclip-prod/pokeclip-prod-render", 0, 1]
      upload = ["ecs", "ecs:service:DesiredCount", "service/pokeclip-prod/pokeclip-prod-upload", 0, 1]
    }
    error_message = "확장 대상이 render · upload 서비스(service/<클러스터>/pokeclip-prod-<키>)마다 원하는 수 0–1 인 둘이 아니다."
  }
}

# 일꾼 → 큐 대응이 지역값 하나(scaling_queue_keys)이고, 키 이름은 작업 역할
# 대상 키(task_resource_keys)와 같은 큐 ARN 키다.
run "scaling_queue_keys_map_workers_to_their_queues" {
  command = plan

  assert {
    condition = local.scaling_queue_keys == {
      render = ["render_queue_arn", "thumbnail_queue_arn"]
      upload = ["upload_queue_arn"]
    }
    error_message = "scaling_queue_keys 가 render → render · thumbnail 큐, upload → upload 큐가 아니다."
  }
}

# 깨우기는 그 일꾼 큐들의 대기(Visible) 합만 본다(계획 쟁점 2 — 0대에서
# NotVisible 은 신호가 아니다). FILL 은 최신 1분을 영구히 채워 경보를 걸 수
# 있어 쓰지 않는다(평가 F-12).
run "wake_alarm_sums_visible_of_own_queues_only" {
  command = plan

  assert {
    condition = alltrue([
      for key, queues in { render = ["pokeclip-prod-jobs-render", "pokeclip-prod-jobs-thumbnail"], upload = ["pokeclip-prod-jobs-upload"] } :
      toset([
        for query in aws_cloudwatch_metric_alarm.queue_wake[key].metric_query :
        "${query.metric[0].namespace} ${query.metric[0].metric_name} ${jsonencode(query.metric[0].dimensions)} ${query.metric[0].stat} ${query.metric[0].period}"
        if length(query.metric) > 0 && query.return_data != true
      ]) == toset([for name in queues : "AWS/SQS ApproximateNumberOfMessagesVisible {\"QueueName\":\"${name}\"} Maximum 60"])
    ])
    error_message = "깨우기 경보의 지표가 그 일꾼 큐들의 ApproximateNumberOfMessagesVisible(Maximum · 60초)만이 아니다 — 남의 큐 · NotVisible 이 섞였거나 빠졌다."
  }

  assert {
    condition = alltrue([
      for alarm in values(aws_cloudwatch_metric_alarm.queue_wake) :
      length([for query in alarm.metric_query : query if query.return_data == true]) == 1 &&
      alltrue([
        for query in alarm.metric_query : (
          sort([for term in split("+", query.expression) : trimspace(term)]) ==
          sort([for other in alarm.metric_query : other.id if length(other.metric) > 0]) &&
          length(query.metric) == 0
        ) if query.return_data == true
      ])
    ])
    error_message = "깨우기 경보의 결과 식이 지표 id 들의 단순 합 하나가 아니다(FILL · 다른 함수 금지)."
  }

  assert {
    condition = {
      for key, alarm in aws_cloudwatch_metric_alarm.queue_wake :
      key => [alarm.alarm_name, alarm.comparison_operator, alarm.threshold, alarm.evaluation_periods, alarm.datapoints_to_alarm, alarm.treat_missing_data]
      } == {
      render = ["pokeclip-prod-render-queue-wake", "GreaterThanThreshold", 0, 1, 1, "notBreaching"]
      upload = ["pokeclip-prod-upload-queue-wake", "GreaterThanThreshold", 0, 1, 1, "notBreaching"]
    }
    error_message = "깨우기 경보가 이름 pokeclip-prod-<키>-queue-wake · GreaterThanThreshold 0 · 1 of 1 · notBreaching 이 아니다."
  }
}

# 재우기는 같은 큐들의 대기 + 처리 중(NotVisible) 합이 30분 내내 0 일 때다
# (평가 F-14). 처리 중인 태스크를 지키는 장치는 이 조건 하나뿐이다(F-11).
# 결측은 유휴로 센다(breaching — F-12). N-of-N 이어야 깨우기와 동시에 ALARM 이
# 되지 않는다(F-13).
run "idle_alarm_sums_visible_and_not_visible_of_own_queues" {
  command = plan

  assert {
    condition = alltrue([
      for key, queues in { render = ["pokeclip-prod-jobs-render", "pokeclip-prod-jobs-thumbnail"], upload = ["pokeclip-prod-jobs-upload"] } :
      toset([
        for query in aws_cloudwatch_metric_alarm.queue_idle[key].metric_query :
        "${query.metric[0].namespace} ${query.metric[0].metric_name} ${jsonencode(query.metric[0].dimensions)} ${query.metric[0].stat} ${query.metric[0].period}"
        if length(query.metric) > 0 && query.return_data != true
        ]) == toset(flatten([
          for name in queues : [
            for metric in ["ApproximateNumberOfMessagesVisible", "ApproximateNumberOfMessagesNotVisible"] :
            "AWS/SQS ${metric} {\"QueueName\":\"${name}\"} Maximum 60"
          ]
      ]))
    ])
    error_message = "재우기 경보의 지표가 그 일꾼 큐들의 Visible · NotVisible(Maximum · 60초)이 아니다 — 남의 큐가 섞였거나 NotVisible 이 빠졌다."
  }

  assert {
    condition = alltrue([
      for alarm in values(aws_cloudwatch_metric_alarm.queue_idle) :
      length([for query in alarm.metric_query : query if query.return_data == true]) == 1 &&
      alltrue([
        for query in alarm.metric_query : (
          sort([for term in split("+", query.expression) : trimspace(term)]) ==
          sort([for other in alarm.metric_query : other.id if length(other.metric) > 0]) &&
          length(query.metric) == 0
        ) if query.return_data == true
      ])
    ])
    error_message = "재우기 경보의 결과 식이 지표 id 들의 단순 합 하나가 아니다."
  }

  assert {
    condition = {
      for key, alarm in aws_cloudwatch_metric_alarm.queue_idle :
      key => [alarm.alarm_name, alarm.comparison_operator, alarm.threshold, alarm.evaluation_periods, alarm.datapoints_to_alarm, alarm.treat_missing_data]
      } == {
      render = ["pokeclip-prod-render-queue-idle", "LessThanThreshold", 1, 30, 30, "breaching"]
      upload = ["pokeclip-prod-upload-queue-idle", "LessThanThreshold", 1, 30, 30, "breaching"]
    }
    error_message = "재우기 경보가 이름 pokeclip-prod-<키>-queue-idle · LessThanThreshold 1 · 30 of 30 · breaching 이 아니다."
  }
}

# 깨우기 = 정확히 1(구간 [0, +∞)), 재우기 = 정확히 0(구간 (-∞, 0] — 데이터
# 없이 ALARM 이 된 경보의 델타 0 도 덮는다, 평가 M-2). 쿨다운은 기본 300초를
# 암묵으로 두지 않는다(F-6).
run "scaling_policies_set_exact_capacity_one_or_zero" {
  command = plan

  assert {
    condition = alltrue(flatten([
      for direction, policies in { wake = aws_appautoscaling_policy.queue_wake, idle = aws_appautoscaling_policy.queue_idle } : [
        for key, policy in policies :
        policy.name == "pokeclip-prod-${key}-queue-${direction}" &&
        policy.policy_type == "StepScaling" &&
        policy.service_namespace == "ecs" &&
        policy.scalable_dimension == "ecs:service:DesiredCount" &&
        policy.resource_id == "service/pokeclip-prod/pokeclip-prod-${key}" &&
        policy.step_scaling_policy_configuration[0].adjustment_type == "ExactCapacity" &&
        policy.step_scaling_policy_configuration[0].cooldown == 60 &&
        policy.step_scaling_policy_configuration[0].metric_aggregation_type == "Maximum"
      ]
    ]))
    error_message = "조절 정책이 pokeclip-prod-<키>-queue-<wake|idle> · StepScaling · 같은 일꾼 서비스 · ExactCapacity · 쿨다운 60 · Maximum 이 아니다."
  }

  assert {
    condition = alltrue([
      for policy in values(aws_appautoscaling_policy.queue_wake) :
      length(policy.step_scaling_policy_configuration[0].step_adjustment) == 1 &&
      alltrue([
        for step in policy.step_scaling_policy_configuration[0].step_adjustment :
        step.metric_interval_lower_bound == "0" && step.metric_interval_upper_bound == null && step.scaling_adjustment == 1
      ])
    ])
    error_message = "깨우기 정책의 단계가 하나(lower 0 · upper 없음 · 1)가 아니다."
  }

  assert {
    condition = alltrue([
      for policy in values(aws_appautoscaling_policy.queue_idle) :
      length(policy.step_scaling_policy_configuration[0].step_adjustment) == 1 &&
      alltrue([
        for step in policy.step_scaling_policy_configuration[0].step_adjustment :
        step.metric_interval_lower_bound == null && step.metric_interval_upper_bound == "0" && step.scaling_adjustment == 0
      ])
    ])
    error_message = "재우기 정책의 단계가 하나(lower 없음 · upper 0 · 0)가 아니다."
  }
}

# 경보 동작이 같은 일꾼 · 같은 방향의 정책 하나다. 정책 ARN 이 apply 때 정해져
# apply 로 돈다. 정책마다 다른 ARN 은 override_resource 로 주는데, 앞 apply
# run 들이 만든 정책은 다시 만들어지지 않아 덮어쓰기가 먹지 않는다 — 그래서
# 상태를 따로 둔다(state_key).
run "scaling_alarms_trigger_own_worker_same_direction_policy" {
  command   = apply
  state_key = "scaling_wiring"

  override_resource {
    target = aws_appautoscaling_policy.queue_wake["render"]
    values = {
      arn = "arn:aws:autoscaling:ap-northeast-2:111122223333:scalingPolicy:wake-render:resource/ecs/service/pokeclip-prod/pokeclip-prod-render:policyName/pokeclip-prod-render-queue-wake"
    }
  }

  override_resource {
    target = aws_appautoscaling_policy.queue_wake["upload"]
    values = {
      arn = "arn:aws:autoscaling:ap-northeast-2:111122223333:scalingPolicy:wake-upload:resource/ecs/service/pokeclip-prod/pokeclip-prod-upload:policyName/pokeclip-prod-upload-queue-wake"
    }
  }

  override_resource {
    target = aws_appautoscaling_policy.queue_idle["render"]
    values = {
      arn = "arn:aws:autoscaling:ap-northeast-2:111122223333:scalingPolicy:idle-render:resource/ecs/service/pokeclip-prod/pokeclip-prod-render:policyName/pokeclip-prod-render-queue-idle"
    }
  }

  override_resource {
    target = aws_appautoscaling_policy.queue_idle["upload"]
    values = {
      arn = "arn:aws:autoscaling:ap-northeast-2:111122223333:scalingPolicy:idle-upload:resource/ecs/service/pokeclip-prod/pokeclip-prod-upload:policyName/pokeclip-prod-upload-queue-idle"
    }
  }

  assert {
    condition = length(distinct(concat(
      [for policy in values(aws_appautoscaling_policy.queue_wake) : policy.arn],
      [for policy in values(aws_appautoscaling_policy.queue_idle) : policy.arn],
    ))) == 4
    error_message = "정책 넷의 ARN 이 서로 달라야 이 run 의 배선 단언이 뜻을 갖는다(mock 값 확인)."
  }

  assert {
    condition = alltrue(flatten([
      for direction, pair in {
        wake = [aws_cloudwatch_metric_alarm.queue_wake, aws_appautoscaling_policy.queue_wake]
        idle = [aws_cloudwatch_metric_alarm.queue_idle, aws_appautoscaling_policy.queue_idle]
        } : [
        for key in ["render", "upload"] :
        tolist(pair[0][key].alarm_actions) == tolist([pair[1][key].arn]) &&
        try(length(pair[0][key].ok_actions), 0) == 0 &&
        try(length(pair[0][key].insufficient_data_actions), 0) == 0
      ]
    ]))
    error_message = "경보 동작이 같은 일꾼 · 같은 방향(wake → queue_wake · idle → queue_idle)의 정책 하나가 아니거나 OK · 데이터 부족 동작이 있다."
  }
}

# 큐 이름은 상수가 아니라 런타임 출력 app_task_resource_arns 의 큐 ARN 끝
# 조각이다 — 런타임이 큐 이름을 바꾸면 경보도 따라간다.
run "scaling_queue_names_come_from_runtime_arn_tail" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        app_env = {
          render = {
            RENDER_QUEUE_URL             = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/q-render-b"
            THUMBNAIL_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/q-thumbnail-b"
            RENDER_VISIBILITY_TIMEOUT    = "600s"
            THUMBNAIL_VISIBILITY_TIMEOUT = "120s"
          }
          upload = {
            UPLOAD_QUEUE_URL          = "https://sqs.ap-northeast-2.amazonaws.com/111122223333/q-upload-b"
            UPLOAD_VISIBILITY_TIMEOUT = "900s"
          }
        }
        app_secret_arns = {
          clip = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          render = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
          upload = {
            INTERNAL_API_TOKEN = "arn:aws:secretsmanager:ap-northeast-2:111122223333:secret:pokeclip/prod/internal-api-token-EfGhIj"
          }
        }
        app_task_resource_arns = {
          render = {
            render_queue_arn    = "arn:aws:sqs:ap-northeast-2:111122223333:q-render-b"
            thumbnail_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:q-thumbnail-b"
            segment_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
            clips_bucket_arn    = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
          upload = {
            upload_queue_arn = "arn:aws:sqs:ap-northeast-2:111122223333:q-upload-b"
            clips_bucket_arn = "arn:aws:s3:::pokeclip-prod-clips-example"
          }
        }
      }
    }
  }

  assert {
    condition = {
      for key, alarm in aws_cloudwatch_metric_alarm.queue_idle :
      key => toset([for query in alarm.metric_query : query.metric[0].dimensions.QueueName if length(query.metric) > 0])
      } == {
      render = toset(["q-render-b", "q-thumbnail-b"])
      upload = toset(["q-upload-b"])
    }
    error_message = "재우기 경보의 큐 이름이 런타임 출력 큐 ARN 의 끝 조각이 아니다."
  }

  assert {
    condition = {
      for key, alarm in aws_cloudwatch_metric_alarm.queue_wake :
      key => toset([for query in alarm.metric_query : query.metric[0].dimensions.QueueName if length(query.metric) > 0])
      } == {
      render = toset(["q-render-b", "q-thumbnail-b"])
      upload = toset(["q-upload-b"])
    }
    error_message = "깨우기 경보의 큐 이름이 런타임 출력 큐 ARN 의 끝 조각이 아니다."
  }
}
