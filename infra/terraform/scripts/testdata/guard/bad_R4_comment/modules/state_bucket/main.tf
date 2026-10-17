# R4: 삭제 방지가 주석 안에만 있다(버킷 자원에는 lifecycle 이 없다).
resource "aws_s3_bucket" "this" {
  bucket = var.name

  /*
  lifecycle {
    prevent_destroy = true
  }
  */
}
