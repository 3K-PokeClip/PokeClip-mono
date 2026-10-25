# Media 상자 user_data(`#cloud-config` → gzip + base64 — EC2 16 KiB 한도는 압축본에
# 걸린다). kty 결정(설치 묶음 이미지): 스크립트 · 상자에서 렌더하는 MediaMTX
# 템플릿은 ECR 설치 묶음 이미지에 있고, user_data 에는 Terraform 이 값을 넣는
# 파일(compose · nginx · ingest-auth.env · media.env · docker 설정) · 유닛 · 작은
# 부트스트랩만 둔다.
#
# 유닛은 묶음이 아니라 여기서 설치한다 — 묶음을 꺼내지 못해도 유닛이 있어야
# drop-in 의 「MEDIA PREREQ FAIL bundle」 이 유닛 저널에 남는다. 유닛은 20초마다
# 기동을 다시 시도하지만 설치는 다시 하지 않는다(부트스트랩은 첫 부팅에 한 번).
# 비밀 값은 어디에도 없다(ARN · 다이제스트 · 판뿐).

locals {
  media_host_dir = "${path.module}/../../../media-host"

  # compose · nginx 템플릿 변수(키 집합 = infra/media-host/test/render_vars.json).
  template_vars = {
    control_upstream       = var.control_upstream
    detached_log_driver    = "journald"
    ingest_auth_image      = var.ingest_auth_image
    ingest_auth_ip_limit   = var.ingest_auth_ip_limit
    janitor_enabled        = var.janitor_enabled
    media_health_path      = var.media_health_path
    mediamtx_image         = var.mediamtx_image
    nginx_image            = var.nginx_image
    proxy_control_port     = var.proxy_ports.control
    proxy_health_port      = var.proxy_ports.health
    proxy_live_port        = var.proxy_ports.live
    proxy_playback_port    = var.proxy_ports.playback
    rds_db_name            = var.rds_db_name
    rds_endpoint           = var.rds_endpoint
    rewind_bucket_name     = split(":::", var.rewind_bucket_arn)[1]
    rewind_public_base_url = var.rewind_public_base_url
    sidecar_image          = var.sidecar_image
    stream_key_resolve_url = var.stream_key_resolve_url
  }

  compose_yml = templatefile("${local.media_host_dir}/compose.yml.tmpl", local.template_vars)
  nginx_conf  = templatefile("${local.media_host_dir}/nginx/nginx.conf.tmpl", local.template_vars)

  bootstrap = templatefile("${path.module}/templates/bootstrap.sh.tftpl", {
    bundle_image                  = var.bundle_image
    compose_sha256                = var.compose_sha256
    compose_version               = var.compose_version
    docker_version                = var.docker_version
    ecr_credential_helper_version = var.ecr_credential_helper_version
  })

  # 상자 키마다 다른 것은 구성 판뿐이다.
  rendered_user_data = {
    for key, instance in var.instances : key => join("", [
      "#cloud-config\n",
      yamlencode({
        write_files = [
          for file in [
            {
              path        = "/etc/pokeclip-media/media.env"
              permissions = "0644"
              content = templatefile("${path.module}/templates/media.env.tftpl", {
                bundle_image                        = var.bundle_image
                cdn_secret_arn                      = var.cdn_secret_arn
                compose_version                     = var.compose_version
                config_version                      = instance.config_version
                denylist_parameter_name             = aws_ssm_parameter.denylist.name
                ecr_registry_host                   = local.ecr_registry_host
                live_port                           = var.proxy_ports.live
                loopback_sender_image               = var.loopback_sender_image
                stream_key_resolve_token_secret_arn = var.stream_key_resolve_token_secret_arn
                test_stream_key_secret_arn          = var.test_stream_key_secret_arn
              })
            },
            { path = "/etc/pokeclip-media/compose.yml", permissions = "0644", content = local.compose_yml },
            { path = "/etc/pokeclip-media/nginx.conf", permissions = "0644", content = local.nginx_conf },
            {
              path        = "/etc/pokeclip-media/ingest-auth.env"
              permissions = "0644"
              content     = templatefile("${local.media_host_dir}/ingest-auth.env.tmpl", { ingest_auth_ip_limit = var.ingest_auth_ip_limit })
            },
            {
              path        = "/root/.docker/config.json"
              permissions = "0600"
              content     = jsonencode({ credHelpers = { (local.ecr_registry_host) = "ecr-login" } })
            },
            {
              path        = "/etc/systemd/system/pokeclip-media.service"
              permissions = "0644"
              content     = file("${local.media_host_dir}/systemd/pokeclip-media.service")
            },
            {
              path        = "/etc/systemd/system/pokeclip-media.service.d/10-bundle.conf"
              permissions = "0644"
              content     = file("${path.module}/templates/10-bundle.conf")
            },
            {
              path        = "/etc/systemd/journald.conf.d/10-pokeclip-media.conf"
              permissions = "0644"
              content     = file("${path.module}/templates/journald.conf")
            },
            { path = "/usr/local/sbin/pokeclip-media-bootstrap", permissions = "0700", content = local.bootstrap },
          ] : merge(file, { owner = "root:root" })
        ]
        runcmd = [["/usr/local/sbin/pokeclip-media-bootstrap"]]
      }),
    ])
  }
}
