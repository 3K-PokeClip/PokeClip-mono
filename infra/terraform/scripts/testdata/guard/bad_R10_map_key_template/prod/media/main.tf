# R10: 맵 키에 템플릿 지시자가 있다 — 키도 문자열 대조 대상이다.
locals {
  tags = {
    "%{if true}x%{endif}" = "y"
  }
}
