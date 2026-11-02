package main

# R14 시험 입력. prod/platform 루트 바로 아래 파일이다.
platform_path := "prod/platform/services.tf"

routed_bridge(path, body) := tf(path, {"resource": {"terraform_data": {
	"public_routed_target_groups": [body],
}}})

waits_for_alb := {
	"input": "${module.public_alb.target_group_arns}",
	"depends_on": ["${module.public_alb}"],
}

test_r14_bridge_waits_for_alb_clean if {
	clean([routed_bridge(platform_path, waits_for_alb)])
}

# 다른 의존과 함께 적어도 공개 ALB 를 기다리면 된다.
test_r14_alb_among_other_dependencies_clean if {
	body := {
		"input": "${module.public_alb.target_group_arns}",
		"depends_on": ["${aws_ecs_cluster.this}", "${module.public_alb}"],
	}
	clean([routed_bridge(platform_path, body)])
}

# 다리는 루트 바로 아래 어느 파일에 있어도 같은 루트다.
test_r14_bridge_in_other_root_file_clean if {
	files := [
		tf(platform_path, {"locals": [{"name_prefix": "pokeclip-prod"}]}),
		routed_bridge("prod/platform/order.tf", waits_for_alb),
	]
	clean(files)
}

test_r14_missing_depends_on if {
	body := {"input": "${module.public_alb.target_group_arns}"}
	count(hits("R14", [routed_bridge(platform_path, body)])) == 1
}

test_r14_empty_depends_on if {
	body := {
		"input": "${module.public_alb.target_group_arns}",
		"depends_on": [],
	}
	count(hits("R14", [routed_bridge(platform_path, body)])) == 1
}

test_r14_depends_on_other_module if {
	body := {
		"input": "${module.public_alb.target_group_arns}",
		"depends_on": ["${module.private_alb}"],
	}
	count(hits("R14", [routed_bridge(platform_path, body)])) == 1
}

# 따옴표 문자열은 참조가 아니다 — Terraform 도 받지 않는다.
test_r14_depends_on_string_not_reference if {
	body := {
		"input": "${module.public_alb.target_group_arns}",
		"depends_on": ["module.public_alb"],
	}
	count(hits("R14", [routed_bridge(platform_path, body)])) == 1
}

test_r14_missing_depends_on_message_names_bridge if {
	body := {"input": "${module.public_alb.target_group_arns}"}
	some msg in hits("R14", [routed_bridge(platform_path, body)])
	startswith(msg, sprintf("R14 %s: %s", [platform_path, `resource "terraform_data" "public_routed_target_groups"`]))
}

# 다리 이름을 바꾸거나 지우면 서비스가 모듈 출력을 곧장 받게 된다.
test_r14_bridge_absent if {
	files := [resource_file(platform_path, "terraform_data", waits_for_alb)]
	count(hits("R14", files)) == 1
}

test_r14_absent_message_names_root if {
	files := [tf(platform_path, {"locals": [{"name_prefix": "pokeclip-prod"}]})]
	some msg in hits("R14", files)
	startswith(msg, "R14 prod/platform: ")
}

# Terraform 은 루트 바로 아래 파일만 읽는다 — 하위 디렉터리의 다리는 세지 않는다.
test_r14_bridge_in_subdirectory_does_not_count if {
	files := [
		tf(platform_path, {"locals": [{"name_prefix": "pokeclip-prod"}]}),
		routed_bridge("prod/platform/extra/order.tf", waits_for_alb),
	]
	count(hits("R14", files)) == 1
}

test_r14_skipped_without_root_files if {
	body := {"input": "${module.public_alb.target_group_arns}"}
	clean([routed_bridge(other, body)])
}

# 하위 디렉터리 파일만 있으면 루트 파일이 없는 것과 같다(건너뜀).
test_r14_skipped_with_only_subdirectory_files if {
	clean([tf("prod/platform/extra/main.tf", {"locals": [{"a": "b"}]})])
}
