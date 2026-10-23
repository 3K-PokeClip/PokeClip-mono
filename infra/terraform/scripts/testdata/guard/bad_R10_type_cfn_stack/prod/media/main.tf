# R10: CloudFormation 스택 안의 AWS::IAM::RolePolicy 로 Media 역할에 Action "*" 를
# 붙인다 — 대상 경로의 자원은 지금 쓰는 유형만(경로 전체 허용 목록).
resource "aws_cloudformation_stack" "extra" {
  name = "pokeclip-prod-media-extra"
  template_body = jsonencode({
    Resources = {
      Extra = {
        Type = "AWS::IAM::RolePolicy"
        Properties = {
          RoleName   = "pokeclip-prod-media"
          PolicyName = "extra"
          PolicyDocument = {
            Version   = "2012-10-17"
            Statement = [{ Effect = "Allow", Action = "*", Resource = "*" }]
          }
        }
      }
    }
  })
}
