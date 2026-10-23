# prod 앱 비밀 그릇 일곱(값 없음)과 서비스별 ARN 지도(POK-305).
# 비밀 값은 운영 단계에서 Terraform 밖으로 넣는다 — 상태에 남기지 않는다(R1).
# 읽기 정책은 두지 않는다 — platform(실행 역할) · media(인스턴스 역할)가 자기
# 정책으로 붙인다(R6 · CONTRACT-runtime.md 「출력과 소유 경계」).
#
# 이 루트가 만들지 않는 것: CLOUDFRONT_PRIVATE_KEY_PEM 은 prod/secrets 의 서명
# 키 그릇을 platform 이 clip 에 합친다. STREAM_KEY_RESOLVE_TOKEN 은 prod 에서
# 비운다 — 응답기는 INTERNAL_API_TOKEN 으로 공용 창구를 쓴다. POSTGRES_PASSWORD
# 는 IAM 인증을 못 쓰는 대체 갈래에서만 생긴다.

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

# 응답기(ingest-auth)는 앱과 같은 INTERNAL_API_TOKEN 그릇을 쓴다(K1 — 새 그릇
# 없음). 한계: Media 상자가 auth 의 /internal/** 전부에 통하는 열쇠를 가진다.
output "media_secret_arns" {
  description = "env 이름 → 비밀 전체 ARN — media 의 인스턴스 역할 읽기 정책 · 응답기 시작 스크립트."
  value = {
    INTERNAL_API_TOKEN = module.secret_shells.secret_arns[local.app_secret_shell_names["INTERNAL_API_TOKEN"]]
  }
}
