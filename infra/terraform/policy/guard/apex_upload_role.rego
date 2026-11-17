# R7: prod/registry 는 apex 업로드 역할(aws_iam_role.apex_upload)의 신원 ·
# 신뢰만 갖는다 — 업로드 권한은 버킷 · 배포가 있는 prod/edge 가 붙인다(설계
# 8절 · prod CI B12). 되돌릴 때 edge 정책을 먼저 지우는 순서도 이 경계에
# 기댄다.
#
# 역할을 가리키는 꼴을 하나하나 막지 않고, 허용 목록으로 본다. registry 안의
# 역할 정책 자원(인라인 · 관리형 연결 · 두 배타 관리 자원)은 role(배타 자원은
# role_name)이 정확히 CI 푸시 역할 참조(aws_iam_role.ci_push.name · .id)여야
# 한다 — 이름 리터럴 · 변수 · 지역값으로 apex 역할을 가리키면 참조 대조를
# 비켜 가기 때문이다. 연결 대상 목록으로 붙이는 aws_iam_policy_attachment 는
# 두지 않고, 역할 본문의 정책 인자(inline_policy 블록 · dynamic ·
# managed_policy_arns)는 값과 무관하게 막는다 — 빈 값은 그 역할의 정책을
# 비우라는 뜻이라 edge 가 붙인 정책을 지운다. 경로는 registry 아래
# 전체다(하위 디렉터리 포함 — 엄격한 쪽). resource 선언만 본다 — module
# 경유 부착은 리뷰 몫이다.
package main

registry_dir := "prod/registry"

# 역할 정책 자원 유형 → 역할을 가리키는 인자.
role_policy_role_keys := {
	"aws_iam_role_policy": "role",
	"aws_iam_role_policy_attachment": "role",
	"aws_iam_role_policies_exclusive": "role_name",
	"aws_iam_role_policy_attachments_exclusive": "role_name",
}

ci_push_role_refs := {"${aws_iam_role.ci_push.name}", "${aws_iam_role.ci_push.id}"}

role_body_policy_keys := {"inline_policy", "managed_policy_arns"}

registry_resources := {decl |
	some decl in declarations
	decl.kind == "resource"
	in_dir(decl.path, registry_dir)
}

targets_ci_push_role(decl) if {
	key := role_policy_role_keys[decl.type]
	decl.body[key] in ci_push_role_refs
}

deny contains msg if {
	some decl in registry_resources
	role_policy_role_keys[decl.type]
	not targets_ci_push_role(decl)
	msg := sprintf(
		"R7 %s: %s 의 %s 가 aws_iam_role.ci_push 참조가 아님 — apex 업로드 역할 권한은 prod/edge 가 붙임",
		[decl.path, label(decl), role_policy_role_keys[decl.type]],
	)
}

deny contains msg if {
	some decl in registry_resources
	decl.type == "aws_iam_policy_attachment"
	msg := sprintf(
		"R7 %s: %s — 연결 대상 목록 정책 연결 금지(역할 권한은 aws_iam_role_policy 로 CI 푸시 역할에만)",
		[decl.path, label(decl)],
	)
}

# 역할 본문에 쓴 정책 인자 이름들(dynamic 블록은 「dynamic inline_policy」).
role_body_policy_args(body) := {key |
	some key in role_body_policy_keys
	key in object.keys(body)
} | {"dynamic inline_policy" |
	"inline_policy" in object.keys(object.get(body, "dynamic", {}))
}

deny contains msg if {
	some decl in registry_resources
	decl.type == "aws_iam_role"
	some key in role_body_policy_args(decl.body)
	msg := sprintf(
		"R7 %s: %s 의 %s 금지(값과 무관 — 역할 권한은 aws_iam_role_policy · apex 업로드는 prod/edge)",
		[decl.path, label(decl), key],
	)
}
