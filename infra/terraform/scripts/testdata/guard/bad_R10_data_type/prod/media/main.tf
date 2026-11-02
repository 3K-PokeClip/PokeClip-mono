# R10: 지금 쓰지 않는 data 유형을 들인다(external — 운영자 자격 증명으로 임의
# 프로그램을 돌린다) — 대상 경로의 data 는 지금 쓰는 유형만.
data "external" "extra" {
  program = ["sh", "-c", "echo {}"]
}
