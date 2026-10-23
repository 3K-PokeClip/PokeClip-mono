# R10: aws 밖의 공급자(awscc)를 들인다 — 대상 경로의 공급자는 hashicorp/aws 만.
terraform {
  required_providers {
    awscc = {
      source  = "hashicorp/awscc"
      version = ">= 1.0.0"
    }
  }
}

provider "awscc" {
  region = "ap-northeast-2"
}
