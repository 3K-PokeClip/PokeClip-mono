# /dvr 하한 KVS 그릇(POK-525) — 이름 · 설명 · 출력 하나를 단언한다. 그릇만
# 만든다 — 키 · 값 자원(aws_cloudfrontkeyvaluestore_key*)이 없음은 guard R2 가
# 본다(없는 자원은 시험이 참조할 수 없다). override_data 가 network 원격 상태를
# 대신하므로 S3 를 읽지 않는다(AWS 호출 0). 계정 자리는 숫자가 아닌 가짜 값이다
# (공유 mock 과 같은 관례 — 계정 ID 숫자열 없음).

mock_provider "aws" {
  source = "./tests/mocks"
}

# auth_oauth_apps 는 기본값이 없다(auth_settings.tf — tfvars 필수). 이 파일의
# 모든 run 에 흐르는 예시 값이다(run 안의 variables 가 덮는 변수만 바뀐다).
variables {
  auth_oauth_apps = {
    google  = { client_id = "example-google-client-id", redirect_uri = "https://app.pokeclip.com/auth/callback" }
    chzzk   = { client_id = "example-chzzk-client-id", redirect_uri = "https://app.pokeclip.com/oauth/chzzk/callback" }
    youtube = { client_id = "example-youtube-client-id", redirect_uri = "https://app.pokeclip.com/oauth/youtube/callback" }
    soop    = { client_id = "example-soop-client-id", redirect_uri = "https://app.pokeclip.com/oauth/soop/callback" }
  }
}

override_data {
  target = data.terraform_remote_state.network
  values = {
    outputs = {
      vpc_id                  = "vpc-0example"
      private_subnet_ids      = ["subnet-0examplea", "subnet-0examplec"]
      media_az                = "ap-northeast-2c"
      media_private_subnet_id = "subnet-0examplec"
    }
  }
}

# arn 은 계산 속성이라 plan 단계에서 고정한다 — 출력이 이 값을 그대로 받는지 본다.
override_resource {
  target          = aws_cloudfront_key_value_store.edge_bounds
  override_during = plan
  values = {
    arn = "arn:aws:cloudfront::example-account:key-value-store/00000000-0000-0000-0000-0000000000e1"
  }
}

# media · edge 는 이름이 아니라 ARN 으로 잇는다. 이름을 바꾸면 그릇이 새로
# 만들어지고(ARN 이 바뀜) 사본이 비므로, 시험이 이름을 고정한다.
run "names_edge_bounds_store" {
  command = plan

  assert {
    condition     = aws_cloudfront_key_value_store.edge_bounds.name == "pokeclip-prod-edge-bounds"
    error_message = "KVS 이름이 pokeclip-prod-edge-bounds 가 아니다."
  }

  assert {
    condition     = aws_cloudfront_key_value_store.edge_bounds.comment == "media: /dvr round floors (values are written by the media sidecar)"
    error_message = "KVS 설명이 계획한 문구가 아니다."
  }
}

# media 가 인스턴스 역할의 EdgeBoundsKvs Resource · 사이드카 env 로 읽는다.
run "outputs_edge_bounds_store_arn" {
  command = plan

  assert {
    condition = (
      output.edge_bounds_kvs_arn == aws_cloudfront_key_value_store.edge_bounds.arn &&
      output.edge_bounds_kvs_arn == "arn:aws:cloudfront::example-account:key-value-store/00000000-0000-0000-0000-0000000000e1"
    )
    error_message = "출력 edge_bounds_kvs_arn 이 KVS ARN 과 다르다."
  }
}
