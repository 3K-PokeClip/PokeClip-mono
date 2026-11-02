# R3: 한 줄 블록 안 속성형 ingress 도 인라인 규칙이다.
resource "aws_security_group" "one_line" { ingress = [{ from_port = 443, to_port = 443, protocol = "tcp", cidr_blocks = ["0.0.0.0/0"], description = null, ipv6_cidr_blocks = null, prefix_list_ids = null, security_groups = null, self = null }] }
