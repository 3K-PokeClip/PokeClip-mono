# 소비 루트 · 운영과의 계약(설계 3-2) — 이름을 바꾸지 않는다. 모듈의 시험용
# 출력(rendered_user_data · template_vars)은 다시 내보내지 않는다.

output "ingest_active_key" {
  description = "NLB ingest 대상 그룹에 등록된 상자 키 — 교체 런북의 일관성 확인."
  value       = var.ingest_active_key
}

output "http_active_key" {
  description = "내부 ALB 대상 그룹 셋에 등록된 상자 키 — 교체 런북의 일관성 확인."
  value       = var.http_active_key
}

output "media_sg_id" {
  description = "Media SG id — 다른 루트가 Media 를 상대로 규칙을 걸 때 쓴다. Media ↔ auth · RDS 짝은 이 루트가 만든다(kty 결정 2026-10-25 (나))."
  value       = module.media_host.media_sg_id
}

output "instance_ids" {
  description = "상자 키 → 인스턴스 id — 교체 런북의 SSM 명령 대상."
  value       = module.media_host.instance_ids
}
