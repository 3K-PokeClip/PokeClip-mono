# R15: prod/edge 의 관리형 SG 조회(data "aws_security_group"
# "vpc_origin_service")는 VPC origin 하나만 기다려야 한다.
#
# 그 SG 는 CloudFront 가 VPC origin 을 처음 만들 때 생기므로, 조회가 VPC
# origin 을 기다리지 않으면 첫 apply 에서 SG 를 찾지 못한다. media 모듈 전체를
# 기다리면 VPC origin 밖 변경마다 조회가 apply 로 밀려 plan 에 인그레스 규칙
# 변경 예고가 끼어든다. terraform test(mock)는 의존 순서를 볼 수 없어 그
# 선언을 여기서 본다 — 그 조회의 depends_on 이 정확히
# [terraform_data.vpc_origin_ready] 여야 한다. 고리의 input 이 VPC origin id
# 인지는 prod/edge/tests/edge.tftest.hcl 이 본다. 조회의 부재는 보지 않는다 —
# 같은 시험이 그 주소를 읽어 이름을 바꾸면 시험이 먼저 깨진다.
package main

edge_root := "prod/edge"

vpc_origin_lookups := {decl |
	some decl in declarations
	decl.kind == "data"
	decl.type == "aws_security_group"
	decl.name == "vpc_origin_service"
	in_root(decl.path, edge_root)
}

waits_for_vpc_origin_only(body) if {
	body.depends_on == ["${terraform_data.vpc_origin_ready}"]
}

deny contains msg if {
	some decl in vpc_origin_lookups
	not waits_for_vpc_origin_only(decl.body)
	msg := sprintf(
		"R15 %s: %s 의 depends_on 이 [terraform_data.vpc_origin_ready] 하나가 아님 — 조회가 VPC origin 보다 먼저 돌거나 media 모듈 변경에 끌려감",
		[decl.path, label(decl)],
	)
}
