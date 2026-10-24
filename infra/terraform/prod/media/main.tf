# prod Media 상자(설계 3-1 의 ⑨ · 3-3-1 · 5-3 · 5-5). Graviton EC2 · Media SG 와
# 짝 규칙(RDS ← Media · 로드밸런서 → Media) · 인스턴스 역할 · 대상 등록은
# modules/media_host 가 만든다. 이 루트는 앞 루트 출력과 이미지 참조를 잇는다.
#
# 교체(활성 키 이동 · 설정 반영 · force)는 runbooks/media-swap.md 를 따른다.

locals {
  # image_digests 키 → registry 저장소 키(pokeclip-prod/<키>).
  image_repository_keys = {
    bundle          = "media-host-bundle"
    mediamtx        = "media-mediamtx"
    nginx           = "media-nginx"
    sidecar         = "media-sidecar"
    loopback_sender = "media-loopback-sender"
    ingest_auth     = "media-ingest-auth"
  }

  # 다이제스트가 빈 응답기는 `…@` 로 조립하지 않고 빈 값으로 넘긴다(렌더 안 함).
  images = {
    for name, key in local.image_repository_keys :
    name => var.image_digests[name] == "" ? "" : "${local.repo_urls[key]}@${var.image_digests[name]}"
  }
}

module "media_host" {
  source = "../../modules/media_host"

  name             = "pokeclip-prod"
  vpc_id           = local.network.vpc_id
  subnet_id        = local.network.media_private_subnet_id
  instance_type    = var.instance_type
  root_volume_size = var.root_volume_size

  instances         = var.instances
  ingest_active_key = var.ingest_active_key
  http_active_key   = var.http_active_key

  target_group_arns  = local.platform.target_group_arns
  internal_alb_sg_id = local.platform.internal_alb_sg_id
  nlb_sg_id          = local.platform.nlb_sg_id
  rds_sg_id          = local.runtime.rds_sg_id
  s3_prefix_list_id  = local.network.s3_prefix_list_id

  # 정본은 platform(대상 그룹 포트 · 헬스와 같은 값).
  proxy_ports       = local.platform.media_proxy_ports
  media_health_path = local.platform.media_health_path
  control_upstream  = var.control_upstream
  janitor_enabled   = var.janitor_enabled

  bundle_image          = local.images.bundle
  mediamtx_image        = local.images.mediamtx
  nginx_image           = local.images.nginx
  sidecar_image         = local.images.sidecar
  loopback_sender_image = local.images.loopback_sender
  ingest_auth_image     = local.images.ingest_auth

  stream_key_resolve_url              = var.stream_key_resolve_url
  stream_key_resolve_token_secret_arn = local.runtime.ingest_auth_resolve_token_secret_arn
  ingest_auth_ip_limit                = var.ingest_auth_ip_limit

  rds_endpoint      = local.runtime.rds_endpoint
  rds_db_name       = local.runtime.rds_db_name
  rds_resource_id   = local.runtime.rds_resource_id
  rewind_bucket_arn = local.runtime.rewind_bucket_arn
  media_secret_arns = local.runtime.media_secret_arns

  rewind_public_base_url = var.rewind_public_base_url

  cdn_secret_arn             = local.secrets.cdn_secret_arn
  test_stream_key_secret_arn = local.secrets.test_stream_key_secret_arn

  docker_version                = var.docker_version
  ecr_credential_helper_version = var.ecr_credential_helper_version
  compose_version               = var.compose_version
  compose_sha256                = var.compose_sha256
}
