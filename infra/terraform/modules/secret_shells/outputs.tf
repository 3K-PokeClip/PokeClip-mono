output "secret_arns" {
  description = "비밀 이름 → ARN."
  value = {
    for name, secret in aws_secretsmanager_secret.this : name => secret.arn
  }
}
