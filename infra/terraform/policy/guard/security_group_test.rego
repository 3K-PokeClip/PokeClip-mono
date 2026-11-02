package main

sample_rule := {"from_port": 443, "to_port": 443, "protocol": "tcp", "cidr_blocks": ["0.0.0.0/0"]}

test_r3_ingress_block if {
	# 블록 `ingress { … }` 와 속성 `ingress = [{ … }]` 는 hcl2 JSON 에서 같은 꼴이다.
	files := [resource_file(other, "aws_security_group", {"name": "example", "ingress": [sample_rule]})]
	count(hits("R3", files)) == 1
}

test_r3_egress_block if {
	files := [resource_file(other, "aws_security_group", {"egress": [sample_rule]})]
	count(hits("R3", files)) == 1
}

test_r3_empty_attribute if {
	files := [resource_file(other, "aws_security_group", {"ingress": []})]
	count(hits("R3", files)) == 1
}

test_r3_null_attribute if {
	files := [resource_file(other, "aws_security_group", {"egress": null})]
	count(hits("R3", files)) == 1
}

test_r3_dynamic_ingress if {
	dyn := {"ingress": [{"for_each": [443], "content": [sample_rule]}]}
	files := [resource_file(other, "aws_security_group", {"dynamic": dyn})]
	count(hits("R3", files)) == 1
}

test_r3_dynamic_egress_empty_for_each if {
	dyn := {"egress": [{"for_each": [], "content": [{}]}]}
	files := [resource_file(other, "aws_security_group", {"dynamic": dyn})]
	count(hits("R3", files)) == 1
}

test_r3_both_reported if {
	body := {"ingress": [sample_rule], "egress": [sample_rule]}
	files := [resource_file(other, "aws_security_group", body)]
	count(hits("R3", files)) == 2
}

test_r3_other_dynamic_allowed if {
	dyn := {"timeouts": [{"for_each": [], "content": [{}]}]}
	clean([resource_file(other, "aws_security_group", {"dynamic": dyn})])
}

test_r3_separate_rule_resources_allowed if {
	clean([tf(other, {"resource": {
		"aws_security_group": {"sg": [{"name": "example"}]},
		"aws_vpc_security_group_ingress_rule": {"https": [{"ip_protocol": "tcp"}]},
		"aws_security_group_rule": {"legacy": [{"type": "ingress"}]},
	}})])
}

test_r3_ingress_in_other_resource_allowed if {
	clean([resource_file(other, "aws_network_acl", {"ingress": [sample_rule]})])
}

# 값 안의 글자 「ingress」(문자열 · 보간 결과)는 속성 이름이 아니다.
test_r3_ingress_in_value_allowed if {
	body := {"description": "${join(\",\", [for ingress in local.ports : ingress])}"}
	clean([resource_file(other, "aws_security_group", body)])
}
