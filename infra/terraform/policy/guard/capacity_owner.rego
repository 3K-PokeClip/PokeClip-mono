# R16: 일꾼 수와 그 조절의 정지 여부는 Terraform 이 아니라 ECS · 자동 조절
# (AAS) · 운영자가 정한다(POK-605). apply 가 그 값을 덮지 않게 lifecycle
# ignore_changes 를 지킨다.
#
# ① modules/ecs_service 의 aws_ecs_service 는 ignore_changes 에 desired_count.
#    desired_count = 1 은 생성 초기값이다. 빠지면 0대일 때마다 plan 에 0 → 1 이
#    보이고 apply 가 일꾼을 되살린다.
# ② prod/workers 의 aws_appautoscaling_target 은 ignore_changes 에
#    suspended_state. 긴급 정지는 운영자가 SuspendedState 를 켜서 한다. 빠지면
#    다음 apply 가 정지를 푼다.
#
# lifecycle 은 plan 결과에 나오지 않아 terraform test(mock)로 볼 수 없으므로
# 여기서 본다. conftest 는 식을 평가하지 않으므로 리터럴 목록 원소만 인정한다 —
# `all` · 다른 속성만 · 따옴표 문자열은 위반이다(ignores 는 media_role.rego).
# 대상 경로에 그 유형의 자원이 없으면 위반이 없다(scaling.tf 가 없는 트리 포함).
package main

capacity_owned_attrs := {
	{"dir": "modules/ecs_service", "type": "aws_ecs_service", "attr": "desired_count"},
	{"dir": "prod/workers", "type": "aws_appautoscaling_target", "attr": "suspended_state"},
}

deny contains msg if {
	some target in capacity_owned_attrs
	some decl in declarations
	decl.kind == "resource"
	decl.type == target.type
	in_dir(decl.path, target.dir)
	not ignores(decl.body, target.attr)
	msg := sprintf(
		"R16 %s: %s 의 lifecycle ignore_changes 에 %s 없음(리터럴) — apply 가 ECS · 자동 조절 · 운영자가 정한 값을 덮음",
		[decl.path, label(decl), target.attr],
	)
}
