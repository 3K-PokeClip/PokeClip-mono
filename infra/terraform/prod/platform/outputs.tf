# 소비 루트와의 계약(설계 3-2 · CONTRACT-runtime.md) — 이름을 바꾸지 않는다.

output "public_alb_arn" {
  description = "공개 ALB ARN."
  value       = module.public_alb.alb_arn
}

output "public_alb_dns_name" {
  description = "공개 ALB DNS 이름 — records 의 app · api 별칭."
  value       = module.public_alb.dns_name
}

output "public_alb_zone_id" {
  description = "공개 ALB 호스팅 존 id — records 의 별칭."
  value       = module.public_alb.zone_id
}

output "public_alb_sg_id" {
  description = "공개 ALB SG id."
  value       = module.public_alb.sg_id
}

output "internal_alb_arn" {
  description = "내부 ALB ARN — edge 의 VPC origin."
  value       = module.internal_alb.alb_arn
}

output "internal_alb_dns_name" {
  description = "내부 ALB DNS 이름 — edge 의 media-alb 원본 도메인."
  value       = module.internal_alb.dns_name
}

output "internal_alb_sg_id" {
  description = "내부 ALB SG id — edge 의 인그레스 ← VPC origin · media 의 이그레스 → Media."
  value       = module.internal_alb.sg_id
}

output "nlb_dns_name" {
  description = "SRT NLB DNS 이름 — records 의 ingest 별칭."
  value       = module.nlb.nlb_dns_name
}

output "nlb_zone_id" {
  description = "SRT NLB 호스팅 존 id — records 의 별칭."
  value       = module.nlb.nlb_zone_id
}

output "nlb_sg_id" {
  description = "SRT NLB SG id — media 의 이그레스 → Media · Media 인그레스 ← NLB."
  value       = module.nlb.nlb_sg_id
}

output "target_group_arns" {
  description = "Media 대상 그룹 ARN(ingest = NLB UDP · live · control · playback = 내부 ALB) — media 가 상자를 등록한다."
  value = {
    ingest   = module.nlb.target_group_arn
    live     = module.internal_alb.target_group_arns["live"]
    control  = module.internal_alb.target_group_arns["control"]
    playback = module.internal_alb.target_group_arns["playback"]
  }
}

output "media_proxy_ports" {
  description = "Media 상자 nginx 듣기 포트(live · control · playback · health) — 대상 그룹 포트 · 헬스와 같은 값. media 가 상자 설정에 쓴다."
  value       = local.media_proxy_ports
}

output "media_health_path" {
  description = "Media nginx 헬스 경로 — 대상 그룹 헬스와 같은 값. media 가 상자 설정에 쓴다."
  value       = local.media_health_path
}

output "ecs_cluster_arn" {
  description = "ECS 클러스터 ARN — 런타임 트랙 · 일꾼(POK-306) · chat(POK-408) 서비스가 같은 클러스터에 둔다."
  value       = aws_ecs_cluster.this.arn
}

output "discovery_namespace_id" {
  description = "Cloud Map 사설 DNS 네임스페이스(pokeclip.internal) id — 일꾼 · chat 서비스 등록."
  value       = aws_service_discovery_private_dns_namespace.this.id
}

output "service_sg_ids" {
  description = "서비스 SG id(auth · clip · next) — 런타임 2단 · 일꾼 · chat 이 상대 규칙에 쓴다."
  value       = local.service_sg_ids
}

output "clip_execution_role_arn" {
  description = "clip 실행 역할 ARN."
  value       = module.clip.execution_role_arn
}
