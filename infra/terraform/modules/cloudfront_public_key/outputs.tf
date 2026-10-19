output "public_key_id" {
  description = "CloudFront 공개키 id — edge 루트의 키 그룹이 쓴다."
  value       = aws_cloudfront_public_key.this.id
}
