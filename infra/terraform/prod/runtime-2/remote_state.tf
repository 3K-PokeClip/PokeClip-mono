# 앞 루트 출력(CONTRACT-runtime.md 「출력과 소유 경계」). 같은 환경 안의 값이라
# terraform_remote_state 로 읽는다.
#
# 런타임 1단(prod/runtime)은 edge 보다 먼저, 이 루트는 edge 뒤에 apply 한다.
# edge 가 1단을 읽고 이 루트가 edge 를 읽어 상태 읽기가 한 방향으로만 흐른다
# (1단 → edge → 2단) — 순환이 없다.

data "terraform_remote_state" "runtime" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.runtime
    region = var.region
  }
}

data "terraform_remote_state" "edge" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = var.state_keys.edge
    region = var.region
  }
}

locals {
  runtime = data.terraform_remote_state.runtime.outputs
  edge    = data.terraform_remote_state.edge.outputs
}

# 앞 루트 출력을 정책에 쓰기 전에 꼴을 본다. 출력 이름이 어긋나 빠진 값도
# 여기서 멈춘다(can 이 false). 정책 자원은 이 자원을 기다려(depends_on), 꼴이
# 틀리면 정책을 plan 하지 않는다.
resource "terraform_data" "remote_contract" {
  lifecycle {
    # 버킷 이름은 S3 이름 규칙의 소문자 · 숫자 · 점 · 하이픈(3–63자)이고, ARN
    # 은 그 이름의 버킷 ARN 이다 — 객체 경로 · 와일드카드가 든 ARN 이면 허용문
    # 자원이 다른 곳을 가리킨다.
    precondition {
      condition = try(
        can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", local.runtime.rewind_bucket_name)) &&
        local.runtime.rewind_bucket_arn == "arn:aws:s3:::${local.runtime.rewind_bucket_name}",
        false,
      )
      error_message = "runtime 출력 rewind_bucket_name · rewind_bucket_arn 이 없거나 버킷 이름 · 그 이름의 버킷 ARN(arn:aws:s3:::<이름>) 꼴이 아니다."
    }

    # 허용문 조건(AWS:SourceArn)이 배포 ARN 과 글자까지 같아야 OAC 서명 요청이
    # 통과한다. 배포 id(E…)만 넘기면 조건이 어떤 요청과도 맞지 않는다.
    precondition {
      condition     = can(regex("^arn:aws:cloudfront::[0-9]{12}:distribution/[A-Z0-9]+$", local.edge.media_distribution_arn))
      error_message = "edge 출력 media_distribution_arn 이 없거나 CloudFront 배포 ARN(arn:aws:cloudfront::<계정>:distribution/<id>) 꼴이 아니다."
    }
  }
}
