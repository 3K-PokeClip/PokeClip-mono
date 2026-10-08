output "dev_eip" {
  description = "dev 상자 EIP 공인 주소 — nonprod/dns-dev 의 apex A 값."
  value       = aws_eip.dev.public_ip
}
