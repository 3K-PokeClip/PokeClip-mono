# prod/runtime-2(런타임 2단)를 mock provider 로 단언한다.
#
# - rewind 버킷 정책은 media 배포의 OAC 읽기 허용문 하나다 — 동작
#   s3:GetObject 만 · 자원 dvr/* · vod/* 만 · 주체 cloudfront.amazonaws.com ·
#   조건 AWS:SourceArn = edge 출력 media_distribution_arn.
# - 다른 문장은 없다. 1단에 rewind 버킷 정책이 없어(rewind_bucket.tf 「두지
#   않는 것」) 보존할 문장이 없고, 작업 역할을 막는 Deny 도 두지 않는다
#   (CONTRACT-runtime.md 「작업 역할 대상」).
# - 앞 루트 출력의 꼴이 어긋나면 terraform_data.remote_contract 가 멈춘다.
# - 두 원격 상태가 각자 자기 루트의 상태 키를 읽는다(runtime · edge).
#
# 앞 루트 상태는 override_data 로 흉내 낸다(AWS 호출 0). 버킷 이름 · ARN ·
# 계정 ID(111122223333)는 가짜 값이다. 버킷 이름을 1단 기본값과 다르게 둬,
# 원격 상태가 아닌 곳에서 이름을 가져오면 실패하게 한다.

mock_provider "aws" {}

override_data {
  target = data.terraform_remote_state.runtime
  values = {
    outputs = {
      rewind_bucket_name = "pokeclip-prod-rewind-example"
      rewind_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example"
    }
  }
}

override_data {
  target = data.terraform_remote_state.edge
  values = {
    outputs = {
      media_distribution_arn = "arn:aws:cloudfront::111122223333:distribution/E0MEDIAEXAMPLE"
    }
  }
}

run "reads_runtime_and_edge_state_keys" {
  command = plan

  assert {
    condition = (
      var.state_keys.runtime == "prod/runtime/terraform.tfstate" &&
      var.state_keys.edge == "prod/edge/terraform.tfstate"
    )
    error_message = "앞 루트 상태 키 기본값이 runtime · edge 의 backend key 와 다르다."
  }

  # 기본값만 보면 데이터 소스가 다른 키를 읽어도(edge 에 runtime 키를 잇는
  # 등) 통과한다. override_data 는 outputs 만 바꾸고 config 는 설정 그대로라
  # 연결을 여기서 본다.
  assert {
    condition     = data.terraform_remote_state.runtime.config.key == "prod/runtime/terraform.tfstate"
    error_message = "runtime 원격 상태가 runtime 상태 키를 읽지 않는다."
  }

  assert {
    condition     = data.terraform_remote_state.edge.config.key == "prod/edge/terraform.tfstate"
    error_message = "edge 원격 상태가 edge 상태 키를 읽지 않는다."
  }
}

run "policy_attaches_to_rewind_bucket_from_runtime_state" {
  command = plan

  assert {
    condition     = aws_s3_bucket_policy.rewind.bucket == "pokeclip-prod-rewind-example"
    error_message = "버킷 정책이 runtime 출력 rewind_bucket_name 의 버킷에 붙지 않았다."
  }
}

run "policy_allows_only_media_oac_read_of_dvr_and_vod" {
  command = plan

  assert {
    condition = jsondecode(aws_s3_bucket_policy.rewind.policy) == jsondecode(jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Sid       = "AllowMediaCloudFrontOacRead"
          Effect    = "Allow"
          Principal = { Service = "cloudfront.amazonaws.com" }
          Action    = "s3:GetObject"
          Resource = [
            "arn:aws:s3:::pokeclip-prod-rewind-example/dvr/*",
            "arn:aws:s3:::pokeclip-prod-rewind-example/vod/*",
          ]
          Condition = {
            StringEquals = {
              "AWS:SourceArn" = "arn:aws:cloudfront::111122223333:distribution/E0MEDIAEXAMPLE"
            }
          }
        },
      ]
    }))
    error_message = "rewind 버킷 정책이 media OAC 의 dvr/* · vod/* 읽기 허용문 하나가 아니다."
  }
}

# 조건 값이 고정 문자열이 아니라 edge 출력을 따르는지 다른 ARN 으로 본다.
run "source_arn_follows_edge_output" {
  command = plan

  override_data {
    target = data.terraform_remote_state.edge
    values = {
      outputs = {
        media_distribution_arn = "arn:aws:cloudfront::111122223333:distribution/E0OTHEREXAMPLE"
      }
    }
  }

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.rewind.policy).Statement[0].Condition.StringEquals["AWS:SourceArn"] == "arn:aws:cloudfront::111122223333:distribution/E0OTHEREXAMPLE"
    error_message = "허용문 조건 AWS:SourceArn 이 edge 출력 media_distribution_arn 을 따르지 않는다."
  }
}

# 문장 수 · Deny 부재를 따로 본다 — 위 전체 비교가 바뀔 때 이 계약이 함께
# 지워지지 않게.
run "policy_has_no_other_statement_and_no_deny" {
  command = plan

  assert {
    condition     = length(jsondecode(aws_s3_bucket_policy.rewind.policy).Statement) == 1
    error_message = "rewind 버킷 정책의 문장이 하나가 아니다 — 문장을 더하면 이 시험과 README 를 함께 고친다."
  }

  assert {
    condition = alltrue([
      for s in jsondecode(aws_s3_bucket_policy.rewind.policy).Statement : s.Effect == "Allow"
    ])
    error_message = "rewind 버킷 정책에 Deny 가 있다 — 작업 역할(clip · 사이드카)을 막지 않아야 한다."
  }
}

# --- 앞 루트 출력의 꼴(terraform_data.remote_contract) ---

run "rejects_missing_media_distribution_arn" {
  command = plan

  override_data {
    target = data.terraform_remote_state.edge
    values = {
      outputs = {}
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "rejects_media_distribution_id_instead_of_arn" {
  command = plan

  override_data {
    target = data.terraform_remote_state.edge
    values = {
      outputs = {
        media_distribution_arn = "E0MEDIAEXAMPLE"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "rejects_non_cloudfront_distribution_arn" {
  command = plan

  override_data {
    target = data.terraform_remote_state.edge
    values = {
      outputs = {
        media_distribution_arn = "arn:aws:cloudfront::111122223333:function/pokeclip-prod-live-auth"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "rejects_rewind_bucket_arn_not_matching_name" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rewind_bucket_name = "pokeclip-prod-rewind-example"
        rewind_bucket_arn  = "arn:aws:s3:::pokeclip-prod-clips-example"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "rejects_rewind_bucket_arn_with_object_path" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {
        rewind_bucket_name = "pokeclip-prod-rewind-example/dvr"
        rewind_bucket_arn  = "arn:aws:s3:::pokeclip-prod-rewind-example/dvr"
      }
    }
  }

  expect_failures = [terraform_data.remote_contract]
}

run "rejects_missing_rewind_bucket_outputs" {
  command = plan

  override_data {
    target = data.terraform_remote_state.runtime
    values = {
      outputs = {}
    }
  }

  expect_failures = [terraform_data.remote_contract]
}
