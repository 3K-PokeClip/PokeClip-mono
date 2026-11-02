# prod 환경(리스 계정 — nonprod 와 같은 계정 안 별도 버킷)의 Terraform 상태
# 버킷. 다른 prod 루트보다 먼저 만든다.
# 절차(로컬 상태 → 자기 버킷으로 이관)는 README.md.

module "state_bucket" {
  source = "../../modules/state_bucket"

  name = var.state_bucket_name
}
