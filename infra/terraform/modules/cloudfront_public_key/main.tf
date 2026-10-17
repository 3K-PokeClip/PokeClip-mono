# CloudFront 서명 쿠키 검증용 공개키. 키 그룹은 edge 루트가 만든다.
# 공개키는 비밀이 아니다. 짝인 개인키(서명 키)는 Secrets Manager 에만 둔다.

resource "aws_cloudfront_public_key" "this" {
  name = var.name

  # 끝 줄바꿈을 하나로 맞춘다 — PEM 파일 끝 줄바꿈의 유무로 encoded_key 가
  # 달라지면 공개키를 교체하는 계획이 나온다.
  encoded_key = "${trimspace(var.public_key_pem)}\n"
}
