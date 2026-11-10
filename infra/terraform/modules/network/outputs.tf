output "vpc_id" {
  description = "VPC id."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC IPv4 CIDR."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "퍼블릭 서브넷 id(azs 순서)."
  value       = [for az in var.azs : aws_subnet.public[az].id]
}

output "private_subnet_ids" {
  description = "프라이빗 서브넷 id(azs 순서)."
  value       = [for az in var.azs : aws_subnet.private[az].id]
}

output "media_public_subnet_id" {
  description = "media_az 의 퍼블릭 서브넷 id(인터넷 대면 NLB)."
  value       = aws_subnet.public[var.media_az].id
}

output "media_private_subnet_id" {
  description = "media_az 의 프라이빗 서브넷 id(Media 상자)."
  value       = aws_subnet.private[var.media_az].id
}

output "s3_prefix_list_id" {
  description = "S3 Gateway 엔드포인트의 접두사 목록 id(SG 이그레스 대상). 엔드포인트를 끄면 null."
  value       = one(aws_vpc_endpoint.s3[*].prefix_list_id)
}

output "interface_endpoint_sg_id" {
  description = "인터페이스 엔드포인트 SG id(호출자 SG 의 이그레스 대상). 엔드포인트가 없으면 null."
  value       = one(aws_security_group.interface_endpoints[*].id)
}

output "interface_endpoint_services" {
  description = "인터페이스 엔드포인트를 둔 서비스 접미(정렬)."
  value       = sort(keys(aws_vpc_endpoint.interface))
}

output "interface_endpoint_private_dns" {
  description = "인터페이스 엔드포인트의 private DNS 가 켜져 있는가(입력 그대로)."
  value       = var.interface_endpoint_private_dns
}
