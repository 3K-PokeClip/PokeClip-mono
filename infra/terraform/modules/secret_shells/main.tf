# 엣지 비밀 그릇(Secrets Manager 비밀 — 값 없음). 이름마다 하나.
# 값(버전)은 운영 단계에서 Terraform 밖으로 넣는다 — 상태에 비밀 값을 남기지
# 않으려고(guard R1). 리소스 정책 · 읽기 역할 입력은 두지 않는다 — 읽기 권한은
# 읽는 쪽 루트(platform · media)가 자기 역할 정책으로 붙인다(guard R6).

resource "aws_secretsmanager_secret" "this" {
  for_each = var.names

  name = each.key
}
