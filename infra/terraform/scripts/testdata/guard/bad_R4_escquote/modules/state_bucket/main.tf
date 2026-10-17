# R4: 보간 속 \" 가 있는 무보호 버킷 — 앞의 보호 버킷에 묻히지 않는다.
resource "aws_s3_bucket" "protected" {
  bucket = "example-a"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket" "this" {
  bucket = lookup({ Note = "${format("%s", "\"")}" }, "Note")
}
