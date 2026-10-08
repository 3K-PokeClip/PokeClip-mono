# R4 · R11: 지워지면 안 되는 자원은 lifecycle { prevent_destroy = true }.
#
# 대상 경로 아래 파일이 하나라도 있으면, 대상 유형마다 자원이 하나 이상
# 있어야 하고 그 자원이 모두 리터럴 true 여야 한다. 식(`true != true` ·
# `var.keep`)과 문자열 "true" 는 위반이다 — conftest 는 식을 평가하지 않으므로
# 엄격하게 리터럴만 인정한다. 대상 경로에 파일이 없는 트리(다른 규칙의
# fixture)는 건너뛴다.
package main

protected_targets := {
	# 상태 버킷.
	{"rule": "R4", "dir": "modules/state_bucket", "type": "aws_s3_bucket"},
	# import 한 dev 상자. 규칙 자원은 M12 가 지우므로 대상이 아니다.
	{"rule": "R11", "dir": "nonprod/dev", "type": "aws_instance"},
	{"rule": "R11", "dir": "nonprod/dev", "type": "aws_eip"},
	{"rule": "R11", "dir": "nonprod/dev", "type": "aws_security_group"},
}

in_dir(path, dir) if startswith(path, concat("", [dir, "/"]))

prevents_destroy(body) if {
	some lifecycle in body.lifecycle
	lifecycle.prevent_destroy == true
}

target_resources(target) := {decl |
	some decl in declarations
	decl.kind == "resource"
	decl.type == target.type
	in_dir(decl.path, target.dir)
}

deny contains msg if {
	some target in protected_targets
	some decl in target_resources(target)
	not prevents_destroy(decl.body)
	msg := sprintf(
		"%s %s: %s 의 lifecycle 에 prevent_destroy = true(리터럴) 없음",
		[target.rule, decl.path, label(decl)],
	)
}

deny contains msg if {
	some target in protected_targets
	some file in input
	in_dir(file.path, target.dir)
	count(target_resources(target)) == 0
	msg := sprintf("%s %s: %s 자원 없음", [target.rule, target.dir, target.type])
}
