package main

media_host_path := "modules/media_host/iam.tf"

media_root_path := "prod/media/main.tf"

ssm_core_arn := "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"

# 사이드카 · janitor 두 DB 사용자로 좁힌 rds-db:connect Resource(for 식 —
# `conftest parse` 실물 꼴).
db_users_resources := concat("", [
	"${[for u in local.db_users : \"arn:${data.aws_partition.current.partition}:rds-db:",
	"${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:",
	"dbuser:${var.rds_resource_id}/${u}\"]}",
])

# 정책 문서 하나만 든 파일. statements 는 statement 블록 배열.
policy_document_file(path, statements) := tf(path, {"data": {"aws_iam_policy_document": {"media": [{"statement": statements}]}}})

db_statement(actions, resources) := {"actions": actions, "resources": resources}

# 통과하는 꼴: DB 사용자 둘로 좁힌 rds-db:connect · GetAuthorizationToken 만 `*` ·
# 관리형 정책은 SSM Core 하나(리터럴과 partition 보간 둘 다).
media_host_clean_files := [
	policy_document_file(media_host_path, [
		db_statement(["rds-db:connect"], db_users_resources),
		db_statement(["ecr:GetAuthorizationToken"], ["*"]),
	]),
	tf("modules/media_host/main.tf", {
		"locals": [{"managed_policy_arns": {"AmazonSSMManagedInstanceCore": ssm_core_arn}}],
		"resource": {"aws_iam_role_policy_attachment": {"managed": [{
			"for_each": "${local.managed_policy_arns}",
			"policy_arn": "${each.value}",
			"role": "${aws_iam_role.this.name}",
		}]}},
	}),
	resource_file(media_root_path, "aws_iam_role_policy_attachment", {
		"policy_arn": "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore",
		"role": "${module.media_host.instance_role_name}",
	}),
]

# 응답기 거부 목록 그릇 — prevent_destroy 리터럴 true · ignore_changes 에 value.
denylist_body := {
	"name": "${var.denylist_parameter_name}",
	"type": "String",
	"value": "# pokeclip deny-tokens v1",
	"lifecycle": [{"ignore_changes": ["${value}"], "prevent_destroy": true}],
}

denylist_file := resource_file("modules/media_host/denylist.tf", "aws_ssm_parameter", denylist_body)

# 역할 정책 규칙만 보는 시험 — 대상 경로에 거부 목록 그릇이 있어야 하는
# 규칙(아래 그릇 시험)이 끼지 않게 정상 그릇을 함께 넣는다.
role_hits(files) := hits("R10", array.concat(files, [denylist_file]))

test_r10_clean if {
	clean(array.concat(media_host_clean_files, [denylist_file]))
}

attachment_file(path, policy_arn) := resource_file(path, "aws_iam_role_policy_attachment", {
	"policy_arn": policy_arn,
	"role": "${aws_iam_role.this.name}",
})

test_r10_managed_policy_outside_allowlist if {
	files := [attachment_file(media_root_path, "arn:aws:iam::aws:policy/AdministratorAccess")]
	msgs := role_hits(files)
	count(msgs) == 1
	some msg in msgs
	contains(msg, "AdministratorAccess")
}

# 이름이 식이면 허용 목록과 대조할 수 없다 — 위반(엄격한 쪽).
test_r10_managed_policy_name_expression if {
	every arn in [
		"arn:aws:iam::aws:policy/${each.key}",
		"arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore${var.suffix}",
		"arn:aws:iam::aws:policy/service-role/AmazonSSMManagedInstanceCore",
	] {
		count(role_hits([attachment_file(media_host_path, arn)])) == 1
	}
}

# 역할 자원의 managed_policy_arns · 지역값 등 어느 자리의 문자열이든 본다.
test_r10_managed_policy_any_position if {
	files := [resource_file(media_host_path, "aws_iam_role", {
		"name": "pokeclip-prod-media",
		"managed_policy_arns": [ssm_core_arn, "arn:aws:iam::aws:policy/AmazonS3FullAccess"],
	})]
	count(role_hits(files)) == 1
}

test_r10_managed_policy_other_dir_ignored if {
	files := [attachment_file("modules/ecs_service/iam.tf", "arn:aws:iam::aws:policy/AdministratorAccess")]
	count(role_hits(files)) == 0
}

test_r10_dbuser_wildcards if {
	every resource in [
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:*/*",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/*",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:*/media_indexer",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/media_?",
	] {
		files := [policy_document_file(media_host_path, [db_statement(["rds-db:connect"], [resource])])]
		msgs := role_hits(files)
		count(msgs) == 1
		some msg in msgs
		contains(msg, "와일드카드")
	}
}

# rds-db:connect 를 덮는 문은 Resource 가 모두 dbuser ARN 이어야 한다.
test_r10_db_statement_resource_not_dbuser if {
	every resources in [["*"], "${var.db_resource_arns}", ["arn:aws:rds-db:*"]] {
		files := [policy_document_file(media_root_path, [db_statement(["rds-db:connect"], resources)])]
		count(role_hits(files)) >= 1
	}
}

# 동작 와일드카드(`rds-db:*` · `*`)도 rds-db:connect 를 덮는다.
test_r10_db_statement_action_wildcard if {
	every actions in [["rds-db:*"], ["*"], ["RDS-DB:Connect"]] {
		files := [policy_document_file(media_host_path, [db_statement(actions, ["*"])])]
		count(role_hits(files)) == 1
	}
}

test_r10_db_statement_without_resources if {
	statement := {"actions": ["rds-db:connect"], "not_resources": ["arn:aws:rds-db:*:*:dbuser:*/admin"]}
	files := [policy_document_file(media_host_path, [statement])]
	count(role_hits(files)) >= 1
}

# 정책 문서 statement 밖의 rds-db 동작(jsonencode 인라인 정책 · dynamic 블록)은
# Resource 를 대조할 수 없어 위반이다.
test_r10_db_action_outside_statement if {
	inline := resource_file(media_host_path, "aws_iam_role_policy", {
		"role": "${aws_iam_role.this.id}",
		"policy": "${jsonencode({Statement = [{Effect = \"Allow\", Action = \"rds-db:connect\", Resource = \"*\"}]})}",
	})
	dynamic := tf(media_host_path, {"data": {"aws_iam_policy_document": {"media": [{"dynamic": {"statement": [{
		"for_each": "${local.db_users}",
		"content": [{"actions": ["rds-db:connect"], "resources": ["*"]}],
	}]}}]}}})
	every files in [[inline], [dynamic]] {
		count(role_hits(files)) >= 1
	}
}

# ARN 속 서비스 이름(`:rds-db:`)은 동작이 아니다.
test_r10_rds_db_arn_is_not_action if {
	files := [tf(media_root_path, {"locals": [{
		"db_user_arn": "arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/media_indexer",
	}]})]
	count(role_hits(files)) == 0
}

test_r10_db_wildcard_other_dir_ignored if {
	files := [policy_document_file("prod/platform/iam.tf", [db_statement(["rds-db:connect"], ["*"])])]
	count(role_hits(files)) == 0
}

# 설명 문자열(description)은 정책이 아니다 — 동작 이름을 적어도 위반이 아니다.
test_r10_description_mentions_action_clean if {
	files := [tf("modules/media_host/variables.tf", {"variable": {"rds_resource_id": [{
		"description": "rds-db:connect Resource 의 dbuser 자리",
		"type": "${string}",
	}]}})]
	count(role_hits(files)) == 0
}

test_r10_denylist_absent if {
	files := [policy_document_file(media_host_path, [db_statement(["ecr:GetAuthorizationToken"], ["*"])])]
	msgs := hits("R10", files)
	count(msgs) == 1
	some msg in msgs
	contains(msg, "aws_ssm_parameter 자원 없음")
}

test_r10_denylist_without_prevent_destroy if {
	every lifecycle in [
		[{"ignore_changes": ["${value}"]}],
		[{"ignore_changes": ["${value}"], "prevent_destroy": "${var.keep}"}],
		[{"ignore_changes": ["${value}"], "prevent_destroy": "true"}],
	] {
		body := object.union(denylist_body, {"lifecycle": lifecycle})
		msgs := hits("R10", [resource_file("modules/media_host/denylist.tf", "aws_ssm_parameter", body)])
		count(msgs) == 1
		some msg in msgs
		contains(msg, "prevent_destroy")
	}
}

# 운영자가 고친 거부 목록을 다음 apply 가 초기값으로 덮지 않게 value 를 무시한다.
test_r10_denylist_without_ignore_value if {
	every lifecycle in [
		[{"prevent_destroy": true}],
		[{"ignore_changes": ["${tags}"], "prevent_destroy": true}],
		[{"ignore_changes": "${var.ignored}", "prevent_destroy": true}],
	] {
		body := object.union(denylist_body, {"lifecycle": lifecycle})
		msgs := hits("R10", [resource_file("modules/media_host/denylist.tf", "aws_ssm_parameter", body)])
		count(msgs) == 1
		some msg in msgs
		contains(msg, "ignore_changes")
	}
}

test_r10_denylist_rules_other_dir_ignored if {
	body := object.union(denylist_body, {"lifecycle": []})
	count(hits("R10", [resource_file("prod/media/main.tf", "aws_ssm_parameter", body)])) == 0
}
