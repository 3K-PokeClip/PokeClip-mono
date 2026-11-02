output "dev_eip" {
  description = "dev 상자 EIP 공인 주소 — 지금 소비자 없음(dns-dev 보류 — 재개 때 apex A 값)."
  value       = aws_eip.dev.public_ip
}
