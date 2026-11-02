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
