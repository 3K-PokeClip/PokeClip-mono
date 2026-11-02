# R14: prod/platform 의 ECS 서비스는 공개 ALB 리스너 규칙이 생긴 뒤에 만들어져야
# 한다(리스너 규칙에 안 붙은 대상 그룹이면 ECS CreateService 가 거부한다).
#
# 서비스는 대상 그룹 ARN 을 다리 terraform_data.public_routed_target_groups 의
# 출력으로 받고, 그 다리가 depends_on 으로 module.public_alb 를 기다린다.
# terraform test 는 의존 간선을 볼 수 없어 그 선언을 여기서 본다 — 루트 바로
# 아래에 주소가 정확히 그 다리인 자원이 있어야 하고, 그 depends_on 에
# module.public_alb 참조가 있어야 한다. 서비스가 다리를 거치는지는
# prod/platform/tests/apply_order.tftest.hcl 이 본다. 루트 바로 아래 파일이
# 없는 트리(다른 규칙의 fixture)는 건너뛴다(R4 와 같이).
package main

platform_root := "prod/platform"

routed_bridge_name := "public_routed_target_groups"

platform_root_files := {file.path |
	some file in input
	in_root(file.path, platform_root)
}

routed_bridges := {decl |
	some decl in declarations
	decl.kind == "resource"
	decl.type == "terraform_data"
	decl.name == routed_bridge_name
	in_root(decl.path, platform_root)
}

waits_for_public_alb(body) if "${module.public_alb}" in body.depends_on

deny contains msg if {
	some decl in routed_bridges
	not waits_for_public_alb(decl.body)
	msg := sprintf(
		"R14 %s: %s 의 depends_on 에 module.public_alb 없음 — 서비스가 리스너 규칙보다 먼저 생길 수 있음",
		[decl.path, label(decl)],
	)
}

deny contains msg if {
	count(platform_root_files) > 0
	count(routed_bridges) == 0
	msg := sprintf(
		"R14 %s: resource %q %q 선언 없음 — 서비스가 리스너 규칙을 기다리는 다리(주소 고정)",
		[platform_root, "terraform_data", routed_bridge_name],
	)
}
