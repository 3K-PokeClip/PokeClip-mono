# R10: SSM Core 밖의 관리형 정책 ARN 을 적었다(어느 자리의 문자열이든 본다).
locals {
  extra_policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}
