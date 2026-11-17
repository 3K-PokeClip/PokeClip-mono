package main

# R7 시험 입력. prod/registry 루트의 CI 역할 파일이다.
registry_roles_path := "prod/registry/ci_roles.tf"

# CI 역할 둘 · 푸시 역할의 인라인 정책만 든 파일(통과하는 꼴). extra 는 더할
# resource 유형 → {이름: [본문]}.
registry_roles(extra) := tf(registry_roles_path, {"resource": object.union(
	{
		"aws_iam_role": {
			"ci_push": [{"name": "pokeclip-prod-ci-ecr-push"}],
			"apex_upload": [{"name": "pokeclip-prod-ci-apex-upload"}],
		},
		"aws_iam_role_policy": {"ci_push": [{
			"name": "pokeclip-prod-ci-ecr-push",
			"role": "${aws_iam_role.ci_push.name}",
			"policy": "${jsonencode({})}",
		}]},
	},
	extra,
)})

# apex 역할 본문에 키를 더한 파일.
registry_apex_role_with(extra_body) := tf(registry_roles_path, {"resource": {"aws_iam_role": {
	"ci_push": [{"name": "pokeclip-prod-ci-ecr-push"}],
	"apex_upload": [object.union({"name": "pokeclip-prod-ci-apex-upload"}, extra_body)],
}}})

test_r7_ci_push_policy_only_clean if {
	clean([registry_roles({})])
}

# 푸시 역할은 id 로 가리켜도 같은 역할이다.
test_r7_ci_push_policy_by_id_clean if {
	extra := {"aws_iam_role_policy": {"ci_push": [{
		"role": "${aws_iam_role.ci_push.id}",
		"policy": "${jsonencode({})}",
	}]}}
	clean([registry_roles(extra)])
}

# 꼴 1: 인라인 정책 자원이 apex 역할을 가리킨다(이름 · id).
test_r7_inline_policy_on_apex_role if {
	every ref in ["${aws_iam_role.apex_upload.name}", "${aws_iam_role.apex_upload.id}"] {
		extra := {"aws_iam_role_policy": {
			"ci_push": [{"role": "${aws_iam_role.ci_push.name}", "policy": "{}"}],
			"apex_upload": [{"role": ref, "policy": "{}"}],
		}}
		count(hits("R7", [registry_roles(extra)])) == 1
	}
}

# 꼴 2: 관리형 정책 연결 자원이 apex 역할을 가리킨다.
test_r7_attachment_on_apex_role if {
	extra := {"aws_iam_role_policy_attachment": {"apex_upload": [{
		"role": "${aws_iam_role.apex_upload.name}",
		"policy_arn": "arn:aws:iam::aws:policy/AmazonS3FullAccess",
	}]}}
	count(hits("R7", [registry_roles(extra)])) == 1
}

# 배타 관리 자원도 그 역할의 정책 집합을 쥔다 — edge 가 붙인 인라인 정책을
# 지울 수 있다.
test_r7_exclusive_resources_on_apex_role if {
	every type in ["aws_iam_role_policies_exclusive", "aws_iam_role_policy_attachments_exclusive"] {
		extra := {type: {"apex_upload": [{"role_name": "${aws_iam_role.apex_upload.name}"}]}}
		count(hits("R7", [registry_roles(extra)])) == 1
	}
}

# 이름 리터럴 · 변수 · 지역값 · 빠진 role 도 푸시 역할 참조가 아니므로 위반이다
# — 이름 문자열로 apex 역할을 가리키면 참조 대조를 비켜 간다.
test_r7_role_not_ci_push_reference if {
	values := [
		"pokeclip-prod-ci-apex-upload",
		"${var.role_name}",
		"${local.apex_role}",
		"aws_iam_role.ci_push.name",
	]
	every value in values {
		extra := {"aws_iam_role_policy": {"other": [{"role": value, "policy": "{}"}]}}
		count(hits("R7", [registry_roles(extra)])) == 1
	}
}

test_r7_role_missing if {
	extra := {"aws_iam_role_policy": {"other": [{"policy": "{}"}]}}
	count(hits("R7", [registry_roles(extra)])) == 1
}

# 연결 대상 목록(roles · users · groups)으로 붙이는 배타 연결 자원은 루트에
# 두지 않는다.
test_r7_policy_attachment_any_target if {
	extra := {"aws_iam_policy_attachment": {"apex": [{
		"name": "apex",
		"roles": ["${aws_iam_role.apex_upload.name}"],
		"policy_arn": "arn:aws:iam::aws:policy/AmazonS3FullAccess",
	}]}}
	count(hits("R7", [registry_roles(extra)])) == 1
}

# 꼴 3: 역할 본문의 정책 인자(블록 · dynamic · 관리형 목록). 빈 값도 위반이다
# — 빈 inline_policy 블록 · 빈 목록은 그 역할의 정책을 비우라는 뜻이라 edge 가
# 붙인 정책을 지운다.
test_r7_role_body_policy_arguments if {
	bodies := [
		{"inline_policy": [{"name": "apex", "policy": "{}"}]},
		{"inline_policy": [{}]},
		{"dynamic": {"inline_policy": [{"for_each": [], "content": [{}]}]}},
		{"managed_policy_arns": ["arn:aws:iam::aws:policy/AmazonS3FullAccess"]},
		{"managed_policy_arns": []},
	]
	every body in bodies {
		count(hits("R7", [registry_apex_role_with(body)])) == 1
	}
}

# 푸시 역할 본문도 같다 — 역할 권한은 aws_iam_role_policy 하나로만 둔다.
test_r7_ci_push_role_body_policy_arguments if {
	file := tf(registry_roles_path, {"resource": {"aws_iam_role": {"ci_push": [{
		"name": "pokeclip-prod-ci-ecr-push",
		"managed_policy_arns": ["arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser"],
	}]}}})
	count(hits("R7", [file])) == 1
}

test_r7_message_names_declaration if {
	extra := {"aws_iam_role_policy": {"apex_upload": [{
		"role": "${aws_iam_role.apex_upload.name}",
		"policy": "{}",
	}]}}
	some msg in hits("R7", [registry_roles(extra)])
	startswith(msg, sprintf("R7 %s: %s", [registry_roles_path, `resource "aws_iam_role_policy" "apex_upload"`]))
}

# 하위 디렉터리도 registry 경로 안이다(엄격한 쪽).
test_r7_registry_subdirectory_checked if {
	file := resource_file("prod/registry/extra/policy.tf", "aws_iam_role_policy", {
		"role": "${aws_iam_role.apex_upload.name}",
		"policy": "{}",
	})
	count(hits("R7", [file])) == 1
}

# 권한은 edge 가 붙인다 — registry 밖 경로 · 이름이 비슷한 경로는 보지 않는다.
test_r7_outside_registry_skipped if {
	every path in ["prod/edge/apex_upload_policy.tf", "prod/registry-old/main.tf", other] {
		file := resource_file(path, "aws_iam_role_policy", {
			"role": "${local.registry.apex_upload_role_name}",
			"policy": "{}",
		})
		clean([file])
	}
}

# data 선언은 부착이 아니다.
test_r7_data_declaration_skipped if {
	file := tf(registry_roles_path, {"data": {"aws_iam_role": {"apex_upload": [{
		"name": "pokeclip-prod-ci-apex-upload",
	}]}}})
	clean([file])
}
