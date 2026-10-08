# R4: heredoc 문자열 속 lifecycle 글자는 삭제 방지가 아니다.
resource "aws_s3_bucket" "this" {
  bucket = "example"
  policy = <<-DOC-TEXT
  lifecycle {
    prevent_destroy = true
  }
  DOC-TEXT
}
