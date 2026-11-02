package main

# R15 시험 입력. prod/edge 루트 바로 아래 파일이다.
edge_root_path := "prod/edge/vpc_origin_ingress.tf"

sg_lookup(path, body) := tf(path, {"data": {"aws_security_group": {
	"vpc_origin_service": [body],
}}})

lookup_body(depends_on) := {
	"name": "CloudFront-VPCOrigins-Service-SG",
	"vpc_id": "${local.network.vpc_id}",
	"depends_on": depends_on,
}

test_r15_waits_for_vpc_origin_only_clean if {
	body := lookup_body(["${terraform_data.vpc_origin_ready}"])
	clean([sg_lookup(edge_root_path, body)])
}

# 조회는 루트 바로 아래 어느 파일에 있어도 같은 루트다.
test_r15_lookup_in_other_root_file_clean if {
	body := lookup_body(["${terraform_data.vpc_origin_ready}"])
	clean([sg_lookup("prod/edge/main.tf", body)])
}

test_r15_missing_depends_on if {
	body := {"name": "CloudFront-VPCOrigins-Service-SG"}
	count(hits("R15", [sg_lookup(edge_root_path, body)])) == 1
}

test_r15_empty_depends_on if {
	body := lookup_body([])
	count(hits("R15", [sg_lookup(edge_root_path, body)])) == 1
}

# 모듈 전체를 기다리면 VPC origin 밖 media 변경마다 조회가 apply 로 밀린다.
test_r15_depends_on_media_module if {
	body := lookup_body(["${module.media}"])
	count(hits("R15", [sg_lookup(edge_root_path, body)])) == 1
}

test_r15_depends_on_vpc_origin_and_media_module if {
	body := lookup_body(["${terraform_data.vpc_origin_ready}", "${module.media}"])
	count(hits("R15", [sg_lookup(edge_root_path, body)])) == 1
}

# 따옴표 문자열은 참조가 아니다 — Terraform 도 받지 않는다.
test_r15_depends_on_string_not_reference if {
	body := lookup_body(["terraform_data.vpc_origin_ready"])
	count(hits("R15", [sg_lookup(edge_root_path, body)])) == 1
}

test_r15_message_names_lookup if {
	body := lookup_body([])
	some msg in hits("R15", [sg_lookup(edge_root_path, body)])
	startswith(msg, sprintf("R15 %s: %s", [edge_root_path, `data "aws_security_group" "vpc_origin_service"`]))
}

# Terraform 은 루트 바로 아래 파일만 읽는다 — 하위 디렉터리 · 다른 루트는 보지 않는다.
test_r15_lookup_in_subdirectory_skipped if {
	clean([sg_lookup("prod/edge/extra/lookup.tf", lookup_body([]))])
}

test_r15_lookup_outside_edge_skipped if {
	clean([sg_lookup(other, lookup_body([]))])
}
