# prod 앱 비밀 그릇 여덟(값 없음)과 서비스별 ARN 지도(POK-305 · POK-429).
# 비밀 값은 운영 단계에서 Terraform 밖으로 넣는다 — 상태에 남기지 않는다(R1).
# 읽기 정책은 두지 않는다 — platform(실행 역할) · media(인스턴스 역할)가 자기
# 정책으로 붙인다(R6 · CONTRACT-runtime.md 「출력과 소유 경계」).
#
# 이 루트가 만들지 않는 것: CLOUDFRONT_PRIVATE_KEY_PEM 은 prod/secrets 의 서명
# 키 그릇을 platform 이 clip 에 합친다. POSTGRES_PASSWORD 는 IAM 인증을 못 쓰는
# 대체 갈래에서만 생긴다.

locals {
  # env 이름 → 그릇 이름. 이름 규약은 pokeclip/prod/<용도>(prod/secrets 와 같음).
  # 같은 env 이름은 서비스가 달라도 같은 그릇이다.
  app_secret_shell_names = {
    JWT_SECRET                 = "pokeclip/prod/jwt-secret"
    SECRET_STORE_KEY           = "pokeclip/prod/secret-store-key"
    INTERNAL_API_TOKEN         = "pokeclip/prod/internal-api-token"
    GOOGLE_CLIENT_SECRET       = "pokeclip/prod/google-client-secret"
    YOUTUBE_CLIENT_SECRET      = "pokeclip/prod/youtube-client-secret"
    CHZZK_CLIENT_SECRET        = "pokeclip/prod/chzzk-client-secret"
    PROFILE_PHOTO_TOKEN_SECRET = "pokeclip/prod/profile-photo-token-secret"
    STREAM_KEY_RESOLVE_TOKEN   = "pokeclip/prod/stream-key-resolve-token"
  }

  # 서비스 → 그 서비스가 읽는 env 이름(application.yml 실물).
  app_secret_env_names = {
    auth = [
      "JWT_SECRET",
      "SECRET_STORE_KEY",
      "INTERNAL_API_TOKEN",
      "GOOGLE_CLIENT_SECRET",
      "YOUTUBE_CLIENT_SECRET",
      "CHZZK_CLIENT_SECRET",
      "PROFILE_PHOTO_TOKEN_SECRET",
      "STREAM_KEY_RESOLVE_TOKEN",
    ]
    clip             = ["JWT_SECRET", "INTERNAL_API_TOKEN"]
    "chat-collector" = ["INTERNAL_API_TOKEN", "CHZZK_CLIENT_SECRET"]
    "chat-detector"  = ["INTERNAL_API_TOKEN"]
  }

  # 서비스 → env 이름 → 그릇 이름.
  app_secret_shells = {
    for service, env_names in local.app_secret_env_names : service => {
      for env_name in env_names : env_name => local.app_secret_shell_names[env_name]
    }
  }
}

module "secret_shells" {
  source = "../../modules/secret_shells"

  names = values(local.app_secret_shell_names)
}

# 출력은 전체 ARN 만 낸다 — 이름 끝의 「-secret」 을 부분 ARN 의 접미로 오인하는
# 혼동을 피한다(prod/secrets README).
output "app_secret_arns" {
  description = "서비스 → env 이름 → 비밀 전체 ARN — platform 의 ecs_service.secrets · 실행 역할 읽기 정책."
  value = {
    for service, shells in local.app_secret_shells : service => {
      for env_name, shell_name in shells :
      env_name => module.secret_shells.secret_arns[shell_name]
    }
  }
}

# Media 는 resolve 전용 토큰 그릇 하나만 읽는다(POK-429 · K2). 그 토큰은 auth
# 의 resolve 하나만 연다. 공용 INTERNAL_API_TOKEN 은 앱 몫이다. 꼴은 소비자
# modules/media_host 의 변수 media_secret_arns(list(string))와 같다.
output "media_secret_arns" {
  description = "비밀 전체 ARN 목록 — media 의 인스턴스 역할 읽기 정책. resolve 전용 토큰(STREAM_KEY_RESOLVE_TOKEN) 그릇 하나."
  value = [
    module.secret_shells.secret_arns[local.app_secret_shell_names["STREAM_KEY_RESOLVE_TOKEN"]],
  ]
}

# 응답기 media.env 렌더(M15)용 이름 있는 출력(K5) — 목록의 순서에 기대지 않게
# 한다. media_secret_arns 의 원소와 같은 ARN 이다.
output "ingest_auth_resolve_token_secret_arn" {
  description = "resolve 전용 토큰 그릇 전체 ARN — 응답기(ingest-auth) media.env 의 STREAM_KEY_RESOLVE_TOKEN_SECRET_ARN 렌더용(M15)."
  value       = module.secret_shells.secret_arns[local.app_secret_shell_names["STREAM_KEY_RESOLVE_TOKEN"]]
}
