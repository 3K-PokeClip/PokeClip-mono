# R13: 이름에 account_id 가 든 변수(계정 ID 는 data.aws_caller_identity 로).
variable "account_id" {
  type = string
}

variable "prod_account_id" {
  type = string
}
