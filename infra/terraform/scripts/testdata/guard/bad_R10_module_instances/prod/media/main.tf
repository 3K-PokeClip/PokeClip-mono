# R10: 루트가 media_host 에 넘기는 instances 를 가공한다 — 키별 cred_relay
# 스위치를 루트에서 덮어 tfvars 와 다른 홉으로 뜬다(POK-574 탐침 E).
module "media_host" {
  source = "../../modules/media_host"

  instances = { for k, v in var.instances : k => merge(v, { cred_relay = true }) }
}
