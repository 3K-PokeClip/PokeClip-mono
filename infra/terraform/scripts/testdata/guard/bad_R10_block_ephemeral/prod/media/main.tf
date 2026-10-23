# R10: ephemeral 블록으로 자원 · data 유형 허용 목록 밖의 공급자 동작을 부른다 —
# 최상위 블록은 Terraform 설정 종류만.
ephemeral "aws_lambda_invocation" "extra" {
  function_name = "pokeclip-prod-extra"
  input         = jsonencode({})
}
