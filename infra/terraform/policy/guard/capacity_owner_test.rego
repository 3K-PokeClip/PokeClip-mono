package main

# R16 시험 입력. 모듈 서비스와 workers 확장 대상의 경로다.
ecs_service_path := "modules/ecs_service/main.tf"

workers_scaling_path := "prod/workers/scaling.tf"

# lifecycle 블록 하나만 다른 서비스 · 확장 대상 파일.
service_file(path, lifecycle) := resource_file(path, "aws_ecs_service", {
	"name": "${local.full_name}",
	"desired_count": 1,
	"lifecycle": lifecycle,
})

scaling_target_file(path, lifecycle) := resource_file(path, "aws_appautoscaling_target", {
	"min_capacity": 0,
	"max_capacity": 1,
	"lifecycle": lifecycle,
})

# 리터럴 목록이 아닌 꼴과 그 속성이 빠진 목록. `all` 은 모든 차이를 숨기므로
# 리터럴 하나로 치지 않는다. 따옴표 문자열은 참조가 아니다.
missing_ignores(attr) := [
	[],
	[{}],
	[{"ignore_changes": []}],
	[{"ignore_changes": ["${task_definition}"]}],
	[{"ignore_changes": "${all}"}],
	[{"ignore_changes": [attr]}],
]

# 메시지 하나만 나오고 그 메시지가 경로 · 속성을 밝힌다.
one_r16(files, path, attr) if {
	msgs := hits("R16", files)
	count(msgs) == 1
	some msg in msgs
	startswith(msg, sprintf("R16 %s: ", [path]))
	contains(msg, attr)
}

test_r16_service_ignores_desired_count_clean if {
	clean([service_file(ecs_service_path, [{"ignore_changes": ["${desired_count}"]}])])
}

test_r16_service_ignores_desired_count_among_others_clean if {
	lifecycle := [{"ignore_changes": ["${task_definition}", "${desired_count}"]}]
	clean([service_file(ecs_service_path, lifecycle)])
}

test_r16_service_missing_desired_count if {
	every lifecycle in missing_ignores("desired_count") {
		one_r16([service_file(ecs_service_path, lifecycle)], ecs_service_path, "desired_count")
	}
}

test_r16_service_without_lifecycle if {
	file := resource_file(ecs_service_path, "aws_ecs_service", {"desired_count": 1})
	one_r16([file], ecs_service_path, "desired_count")
}

# 하위 디렉터리여도 모듈 경로 안이다.
test_r16_service_in_module_subdirectory if {
	path := "modules/ecs_service/extra/main.tf"
	one_r16([service_file(path, [])], path, "desired_count")
}

# platform 은 모듈을 부를 뿐 서비스를 직접 선언하지 않는다 — 모듈 밖은 보지
# 않는다.
test_r16_service_other_dir_ignored if {
	every path in ["prod/platform/services.tf", "modules/ecs_service_x/main.tf", other] {
		count(hits("R16", [service_file(path, [])])) == 0
	}
}

test_r16_scaling_target_ignores_suspended_state_clean if {
	lifecycle := [{"ignore_changes": ["${suspended_state}"]}]
	clean([scaling_target_file(workers_scaling_path, lifecycle)])
}

test_r16_scaling_target_missing_suspended_state if {
	every lifecycle in missing_ignores("suspended_state") {
		one_r16([scaling_target_file(workers_scaling_path, lifecycle)], workers_scaling_path, "suspended_state")
	}
}

test_r16_scaling_target_other_dir_ignored if {
	every path in ["prod/platform/scaling.tf", "modules/ecs_service/scaling.tf", other] {
		count(hits("R16", [scaling_target_file(path, [])])) == 0
	}
}

# scaling.tf 가 없는 트리(c2 전 · 되돌린 뒤)는 위반이 없다.
test_r16_no_scaling_target_clean if {
	clean([tf("prod/workers/main.tf", {"locals": [{"name_prefix": "pokeclip-prod"}]})])
}

# 두 대상이 함께 빠지면 자원마다 하나씩 알린다.
test_r16_both_missing_two_hits if {
	files := [
		service_file(ecs_service_path, []),
		scaling_target_file(workers_scaling_path, []),
	]
	count(hits("R16", files)) == 2
}
