# 소비 루트와의 계약(설계 3-2 · 3-6) — 이름을 바꾸지 않는다.

output "vpc_id" {
  description = "prod VPC id — 런타임 1단(RDS SG) · platform · media · edge(서울 별칭 data source)."
  value       = module.network.vpc_id
}

output "vpc_cidr" {
  description = "prod VPC CIDR."
  value       = module.network.vpc_cidr
}

output "public_subnet_ids" {
  description = "퍼블릭 서브넷 id 두 개(azs 순서) — platform 의 공개 ALB."
  value       = module.network.public_subnet_ids
}

output "private_subnet_ids" {
  description = "프라이빗 서브넷 id 두 개(azs 순서) — 런타임 1단 RDS 서브넷 그룹 · platform 의 내부 ALB · ECS."
  value       = module.network.private_subnet_ids
}

output "media_az" {
  description = "Media 상자 · NLB 의 AZ."
  value       = var.media_az
}

output "media_public_subnet_id" {
  description = "media_az 의 퍼블릭 서브넷 id — platform 의 NLB."
  value       = module.network.media_public_subnet_id
}

output "media_private_subnet_id" {
  description = "media_az 의 프라이빗 서브넷 id — media 의 Media 상자."
  value       = module.network.media_private_subnet_id
}

output "s3_prefix_list_id" {
  description = "S3 Gateway 엔드포인트 접두사 목록 id — media 의 SG 이그레스(rewind 업로드)."
  value       = module.network.s3_prefix_list_id
}
