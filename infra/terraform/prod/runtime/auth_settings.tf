# auth 의 비밀 아닌 prod 설정(POK-427 · 설계 D7 · D8 · D9 · K7) — OAuth 네
# 덩어리(google · chzzk · youtube · soop)의 CLIENT_ID · REDIRECT_URI 와 프로필
# 사진 창고 이름 · 주소. 비밀(CLIENT_SECRET 넷)은 app_secret_arns 의 그릇이다.
#
# 값은 kty 의 terraform.tfvars 다(커밋하지 않음 — infra/terraform/.gitignore).
# 콘솔에 등록한 뒤에 값을 바꾼다 — 거꾸로 하면 동의가 거부된다(services
# README 「OAuth 리다이렉트 · 출처 값」).
#
# 검증은 앱의 부팅 검사를 plan 으로 당긴다(D9). 틀리면 auth 가 부팅을 거부하거나
# 사진만 조용히 안 보인다.

variable "auth_oauth_apps" {
  description = "OAuth 앱 넷(google · chzzk · youtube · soop) → client_id · redirect_uri. 기본값 없음 — tfvars 필수(services README 의 prod 값)."
  type = object({
    google = object({
      client_id    = string
      redirect_uri = string
    })
    chzzk = object({
      client_id    = string
      redirect_uri = string
    })
    youtube = object({
      client_id    = string
      redirect_uri = string
    })
    soop = object({
      client_id    = string
      redirect_uri = string
    })
  })

  # 덩어리 하나라도 비면 auth 가 부팅을 거부한다(services README — 한 덩어리 검증).
  validation {
    condition = alltrue([
      for app in values(var.auth_oauth_apps) : can(regex("^\\S+$", app.client_id))
    ])
    error_message = "auth_oauth_apps 의 client_id 가 비었거나 공백이 들어 있다 — auth 가 부팅을 거부한다."
  }

  # 구글은 http 리디렉션을 localhost 에만 허용한다(services README). prod 는
  # https 절대 주소이고 localhost 를 가리키지 않는다.
  validation {
    condition = alltrue([
      for app in values(var.auth_oauth_apps) : (
        can(regex("^https://[^\\s/?#@]+(/\\S*)?$", app.redirect_uri)) &&
        !can(regex("^https://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]+)?([/?#]|$)", lower(app.redirect_uri)))
      )
    ])
    error_message = "auth_oauth_apps 의 redirect_uri 가 https:// 절대 주소가 아니거나 localhost 를 가리킨다."
  }
}

# 같은-오리진 리라이트의 앱 호스트가 기본이다(K7 · dev 와 같은 꼴). platform 의
# app_host 와 값이 두 군데다.
variable "profile_photo_base_url" {
  description = "프로필 사진 주소의 앞부분(브라우저가 붙는 http(s) 절대 주소 — 끝 / · 쿼리 · 조각 없음)."
  type        = string
  default     = "https://app.pokeclip.com"

  # PhotoProperties.java requireAbsoluteUrl 과 같은 규칙이다 — 바꾸면 두 곳을
  # 함께 본다. 경로(/sub)는 막지 않는다(앱도 막지 않음).
  validation {
    condition = (
      can(regex("^(?i)https?://[^\\s/?#]+(/[^\\s?#]*)?$", var.profile_photo_base_url)) &&
      !endswith(var.profile_photo_base_url, "/")
    )
    error_message = "profile_photo_base_url 이 http(s):// 절대 주소가 아니거나 끝에 / · 쿼리 · 조각이 있다 — auth 가 부팅을 거부한다."
  }
}

locals {
  # app_env.auth 에 합친다(app_env.tf). 이름은 auth application.yml 실물이다.
  auth_settings_env = {
    GOOGLE_CLIENT_ID        = var.auth_oauth_apps.google.client_id
    GOOGLE_REDIRECT_URI     = var.auth_oauth_apps.google.redirect_uri
    CHZZK_CLIENT_ID         = var.auth_oauth_apps.chzzk.client_id
    CHZZK_REDIRECT_URI      = var.auth_oauth_apps.chzzk.redirect_uri
    YOUTUBE_CLIENT_ID       = var.auth_oauth_apps.youtube.client_id
    YOUTUBE_REDIRECT_URI    = var.auth_oauth_apps.youtube.redirect_uri
    SOOP_CLIENT_ID          = var.auth_oauth_apps.soop.client_id
    SOOP_REDIRECT_URI       = var.auth_oauth_apps.soop.redirect_uri
    PROFILE_PHOTO_S3_BUCKET = aws_s3_bucket.profile_photo.bucket
    PROFILE_PHOTO_BASE_URL  = var.profile_photo_base_url
  }
}
