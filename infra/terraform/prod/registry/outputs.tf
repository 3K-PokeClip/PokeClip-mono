# 소비 루트와의 계약(설계 3-2) — 이름을 바꾸지 않는다.

output "repo_urls" {
  description = "저장소 키 → 저장소 URL(<레지스트리 호스트>/pokeclip-prod/<키>) — platform · media 의 이미지 주소 · kty 로컬 푸시."
  value       = { for key, repository in aws_ecr_repository.this : key => repository.repository_url }
}
