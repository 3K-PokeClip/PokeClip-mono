# prod/workers 루트(POK-408 — 설계 2-3 · 3절 · 7-3)를 mock provider 로
# 단언한다 — 이미지 다이제스트 고정 · 크기 · prod 경로, env · 비밀 · 이그레스 ·
# 작업 역할 문장이 계약(CONTRACT-runtime.md 「일꾼 작업 역할 대상」)과 정확히
# 같은지, 상대 SG 인그레스 셋, 런타임 계약 검사(terraform_data.runtime_contract)
# 가 어긋난 앞 루트 출력에서 plan 을 멈추는지.
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
      vpc_id             = "vpc-0example"
      private_subnet_ids = ["subnet-0priva", "subnet-0privc"]
      s3_prefix_list_id  = "pl-0example"
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
    }
    error_message = "render env 가 런타임 app_env.render 넷 + CLIP_BASE_URL(clip Cloud Map 이름 · platform service_ports.clip)과 다르다."
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
    }
    error_message = "upload env 가 런타임 app_env.upload 둘 + CLIP_BASE_URL · AUTH_BASE_URL 과 다르다."
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
      aws_api = { port = 443, cidr_ipv4 = "0.0.0.0/0", description = "AWS APIs via NAT" }
      s3      = { port = 443, prefix_list_id = "pl-0example", description = "S3 gateway endpoint" }
      clip    = { port = 28081, security_group_id = "sg-0clip", description = "render to clip" }
    }
    error_message = "render 이그레스가 aws_api · s3(platform common_egress 와 같은 값) · clip 셋이 아니다."
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
