output "web_acl_arn" {
  description = "Web ACL ARN(ALB 연결 · CloudFront web_acl_id 에 넘긴다)."
  value       = aws_wafv2_web_acl.this.arn
}
