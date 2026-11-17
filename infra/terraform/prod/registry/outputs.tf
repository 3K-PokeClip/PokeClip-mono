# 소비 루트와의 계약(설계 3-2) — 이름을 바꾸지 않는다.

output "repo_urls" {
  description = "저장소 키 → 저장소 URL(<레지스트리 호스트>/pokeclip-prod/<키>) — platform · media 의 이미지 주소 · kty 로컬 푸시."
  value       = { for key, repository in aws_ecr_repository.this : key => repository.repository_url }
}

output "ci_push_role_arn" {
  description = "CI 푸시 역할 ARN — GitHub environment prod-deploy 비밀(이미지 워크플로의 역할 받기)."
  value       = aws_iam_role.ci_push.arn
}

output "apex_upload_role_name" {
  description = "apex 업로드 역할 이름 — prod/edge 가 이 역할에 업로드 정책을 붙인다."
  value       = aws_iam_role.apex_upload.name
}

output "apex_upload_role_arn" {
  description = "apex 업로드 역할 ARN — GitHub environment prod-deploy 비밀(랜딩 워크플로의 역할 받기)."
  value       = aws_iam_role.apex_upload.arn
}
