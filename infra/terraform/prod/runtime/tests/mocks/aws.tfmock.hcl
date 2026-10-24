# 시험 열셋(tests/*.tftest.hcl 전부)이 함께 쓰는 aws mock 기본값. 구독의
# topic_arn 은 provider 가 ARN 형식으로 검사해 mock 의 임의 문자열을
# 거부하므로 주제 ARN 만 형식을 갖춘 가짜 값으로 준다(계정 자리는 비운다 —
# 계정 ID 숫자열 없음).
mock_resource "aws_sns_topic" {
  defaults = {
    arn = "arn:aws:sns:ap-northeast-2::example-broadcast-lifecycle.fifo"
  }
}
