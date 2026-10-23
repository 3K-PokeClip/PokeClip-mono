# Media 상자(설계 3-1 의 ⑨ · 3-3 · 3-3-1 · 5-3). Graviton(arm64) EC2 를 Media AZ
# 프라이빗 서브넷에 두고, 대상 그룹 넷(NLB ingest · 내부 ALB live · control ·
# playback)에는 활성 키의 상자 하나만 등록한다(단일 활성 서버 — 무감각 교체 없음).
#
# 상자 키마다 구성 판(config_version)을 둔다. 이미 떠 있는 키의 user_data ·
# AMI 는 바꾸지 않고(ignore_changes), 새 구성은 새 키로 띄운 뒤 활성 키를
# 옮긴다(5-3 교체 런북).

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_partition" "current" {}

data "aws_ssm_parameter" "ami" {
  name = var.ami_ssm_parameter_name
}

# 상자는 arm64 다(AMI · 이미지 · compose · 자격 도우미가 모두 arm64 판).
data "aws_ec2_instance_type" "this" {
  instance_type = var.instance_type
}

locals {
  account_id        = data.aws_caller_identity.current.account_id
  region            = data.aws_region.current.region
  partition         = data.aws_partition.current.partition
  ecr_registry_host = "${local.account_id}.dkr.ecr.${local.region}.amazonaws.com"

  # 상자가 받는 이미지 — 모두 이 계정 ECR 이어야 한다(응답기는 비면 뺀다).
  image_refs = merge(
    {
      bundle_image          = var.bundle_image
      mediamtx_image        = var.mediamtx_image
      nginx_image           = var.nginx_image
      sidecar_image         = var.sidecar_image
      loopback_sender_image = var.loopback_sender_image
    },
    var.ingest_auth_image == "" ? {} : { ingest_auth_image = var.ingest_auth_image },
  )
  foreign_images = [
    for key, ref in local.image_refs : key
    if !startswith(ref, "${local.ecr_registry_host}/")
  ]

  http_target_ports = {
    live     = var.proxy_ports.live
    control  = var.proxy_ports.control
    playback = var.proxy_ports.playback
  }
}

resource "aws_instance" "this" {
  for_each = var.instances

  ami                         = data.aws_ssm_parameter.ami.insecure_value
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [aws_security_group.media.id]
  iam_instance_profile        = aws_iam_instance_profile.media.name
  associate_public_ip_address = false
  user_data_base64            = base64gzip(local.rendered_user_data[each.key])
  user_data_replace_on_change = false

  # 컨테이너(사이드카 IAM DB 토큰 · S3 업로드)가 인스턴스 역할 자격을 얻도록
  # hop limit 2(설계 3-3-1 · 계획 G22).
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    encrypted             = true
    delete_on_termination = true
    volume_type           = "gp3"
    volume_size           = var.root_volume_size
    throughput            = var.root_volume_throughput
  }

  tags = {
    Name          = "${var.name}-media-${each.key}"
    ConfigVersion = each.value.config_version
  }

  lifecycle {
    # 5-3 교체 모델: 떠 있는 상자는 그대로 두고 새 구성은 새 키로.
    ignore_changes = [ami, user_data_base64]

    precondition {
      condition     = contains(data.aws_ec2_instance_type.this.supported_architectures, "arm64")
      error_message = "instance_type 은 arm64(Graviton) 유형이어야 한다: ${var.instance_type}"
    }

    precondition {
      condition     = length(local.foreign_images) == 0
      error_message = "이미지 참조는 이 계정 ECR 레지스트리 호스트(<계정>.dkr.ecr.<리전>.amazonaws.com/)로 시작해야 한다: ${join(", ", local.foreign_images)}"
    }
  }
}

resource "aws_lb_target_group_attachment" "ingest" {
  target_group_arn = var.target_group_arns.ingest
  target_id        = aws_instance.this[var.ingest_active_key].id
  port             = var.srt_port
}

resource "aws_lb_target_group_attachment" "http" {
  for_each = local.http_target_ports

  target_group_arn = var.target_group_arns[each.key]
  target_id        = aws_instance.this[var.http_active_key].id
  port             = each.value
}
