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

# 관리형 정책 연결 — 주소 managed · for_each = 지역값 · policy_arn = each.value.
managed_locals := {"managed_policy_arns": {"AmazonSSMManagedInstanceCore": ssm_core_arn}}

managed_body := {
	"for_each": "${local.managed_policy_arns}",
	"policy_arn": "${each.value}",
	"role": "${aws_iam_role.this.name}",
}

# 지역값과 연결 자원 하나(이름 name)를 든 파일.
attachment_file(path, locals, name, body) := tf(path, {
	"locals": [locals],
	"resource": {"aws_iam_role_policy_attachment": {name: [body]}},
})

# 문자열 하나를 지역값으로만 든 파일(문자열 규칙만 보는 시험).
locals_file(path, value) := tf(path, {"locals": [{"value": value}]})

# 통과하는 꼴: DB 사용자 둘로 좁힌 rds-db:connect · GetAuthorizationToken 만 `*` ·
# 관리형 정책은 SSM Core 하나 · 권한 정책은 정책 문서 json · 신뢰 정책은 jsonencode.
media_host_clean_files := [
	policy_document_file(media_host_path, [
		db_statement(["rds-db:connect"], db_users_resources),
		db_statement(["ecr:GetAuthorizationToken"], ["*"]),
	]),
	attachment_file("modules/media_host/main.tf", managed_locals, "managed", managed_body),
	tf("modules/media_host/role.tf", {"resource": {
		"aws_iam_role": {"media": [{
			"name": "pokeclip-prod-media",
			"assume_role_policy": "${jsonencode({Statement = [{Action = \"sts:AssumeRole\"}]})}",
		}]},
		"aws_iam_role_policy": {"media": [{
			"role": "${aws_iam_role.media.id}",
			"policy": "${data.aws_iam_policy_document.media.json}",
		}]},
		"aws_iam_instance_profile": {"media": [{"role": "${aws_iam_role.media.name}"}]},
	}}),
	locals_file(media_root_path, "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"),
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

# 메시지 하나만 나오고 그 메시지에 needle 이 든다.
one_hit(files, needle) if {
	msgs := role_hits(files)
	count(msgs) == 1
	some msg in msgs
	contains(msg, needle)
}

test_r10_clean if {
	clean(array.concat(media_host_clean_files, [denylist_file]))
}

test_r10_managed_policy_outside_allowlist if {
	one_hit([locals_file(media_root_path, "arn:aws:iam::aws:policy/AdministratorAccess")], "AdministratorAccess")
}

# 이름이 식이면 허용 목록과 대조할 수 없다 — 위반(엄격한 쪽).
test_r10_managed_policy_name_expression if {
	every arn in [
		"arn:aws:iam::aws:policy/${each.key}",
		"arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore${var.suffix}",
		"arn:aws:iam::aws:policy/service-role/AmazonSSMManagedInstanceCore",
	] {
		one_hit([locals_file(media_host_path, arn)], "관리형 정책")
	}
}

test_r10_managed_policy_other_dir_ignored if {
	files := [locals_file("modules/ecs_service/iam.tf", "arn:aws:iam::aws:policy/AdministratorAccess")]
	count(role_hits(files)) == 0
}

# 관리형 정책 ARN 을 문자열로 적지 않는 길(정책 조회 · 고객 관리 정책 · 다른
# 연결 자원)은 IAM 유형 허용 목록이 막는다.
test_r10_iam_type_outside_allowlist if {
	every file in [
		tf(media_root_path, {"data": {"aws_iam_policy": {"admin": [{"name": "AdministratorAccess"}]}}}),
		resource_file(media_root_path, "aws_iam_policy", {"policy": "${data.aws_iam_policy_document.media.json}"}),
		resource_file(media_root_path, "aws_iam_policy_attachment", {"policy_arn": "${var.arn}"}),
		resource_file(media_root_path, "aws_iam_role_policy_attachments_exclusive", {"policy_arns": "${var.arns}"}),
	] {
		one_hit([file], "IAM 유형")
	}
}

test_r10_attachment_name_not_managed if {
	one_hit([attachment_file(media_host_path, managed_locals, "extra", managed_body)], "주소는 managed")
}

test_r10_attachment_policy_arn_expression if {
	every arn in [
		"${format(\"%s\", each.value)}",
		"${var.policy_arn}",
		"${each.key}",
	] {
		body := object.union(managed_body, {"policy_arn": arn})
		one_hit([attachment_file(media_host_path, managed_locals, "managed", body)], "each.value")
	}
}

test_r10_attachment_for_each_not_local if {
	body := object.union(managed_body, {"for_each": "${var.managed_policy_arns}"})
	one_hit([attachment_file(media_host_path, managed_locals, "managed", body)], "local.managed_policy_arns")
}

# managed 의 지역값은 키 · 값 모두 SSM Core 하나(값은 리터럴 ARN).
test_r10_managed_locals_not_ssm_core_only if {
	every locals in [
		{"managed_policy_arns": {"AmazonSSMManagedInstanceCore": ssm_core_arn, "Extra": ssm_core_arn}},
		{"managed_policy_arns": {"AmazonSSMManagedInstanceCore": "${var.arn}"}},
		{"managed_policy_arns": "${var.arns}"},
		{"other": "x"},
	] {
		one_hit([attachment_file(media_host_path, locals, "managed", managed_body)], "managed_policy_arns")
	}
}

test_r10_managed_locals_accept_partition_reference if {
	every arn in [
		ssm_core_arn,
		"arn:${local.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore",
		"arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore",
	] {
		locals := {"managed_policy_arns": {"AmazonSSMManagedInstanceCore": arn}}
		count(role_hits([attachment_file(media_host_path, locals, "managed", managed_body)])) == 0
	}
}

# 정책 문서는 허용 동작 · Resource 를 그대로 적는다 — 부정형 · 다른 문서 합치기 ·
# dynamic 블록은 대조를 비켜 간다.
test_r10_policy_document_negations if {
	every statement in [
		{"not_actions": ["iam:*"], "resources": ["*"]},
		{"actions": ["s3:GetObject"], "not_resources": ["arn:aws:s3:::x/*"]},
	] {
		one_hit([policy_document_file(media_host_path, [statement])], "not_")
	}
}

test_r10_policy_document_merges_and_dynamic if {
	every body in [
		{"source_policy_documents": ["${jsonencode({})}"]},
		{"override_policy_documents": ["${var.extra}"]},
		{"dynamic": {"statement": [{"for_each": "${var.x}", "content": [{"actions": ["s3:GetObject"], "resources": ["*"]}]}]}},
	] {
		one_hit([tf(media_host_path, {"data": {"aws_iam_policy_document": {"media": [body]}}})], "정책 문서")
	}
}

# 권한 정책은 정책 문서 json 으로만 붙인다. 신뢰 정책(assume_role_policy)은 예외.
test_r10_inline_policy_not_from_document if {
	every file in [
		resource_file(media_host_path, "aws_iam_role_policy", {
			"role": "${aws_iam_role.this.id}",
			"policy": "${jsonencode({Statement = [{Effect = \"Allow\", Action = \"s3:*\", Resource = \"*\"}]})}",
		}),
		resource_file(media_host_path, "aws_iam_role_policy", {"role": "${aws_iam_role.this.id}", "policy": "${var.policy}"}),
		resource_file(media_host_path, "aws_iam_role", {"name": "x", "inline_policy": [{"name": "x", "policy": "${data.aws_iam_policy_document.media.json}"}]}),
		resource_file(media_host_path, "aws_iam_role", {"name": "x", "managed_policy_arns": "${var.arns}"}),
	] {
		one_hit([file], "인라인")
	}
}

test_r10_dbuser_wildcards if {
	every resource in [
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:*/*",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/*",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:*/media_indexer",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/media_?",
	] {
		files := [policy_document_file(media_host_path, [db_statement(["rds-db:connect"], [resource])])]
		one_hit(files, "와일드카드")
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
		one_hit(files, "dbuser ARN 만")
	}
}

test_r10_db_statement_without_resources if {
	statement := {"actions": ["rds-db:connect"]}
	one_hit([policy_document_file(media_host_path, [statement])], "resources 없음")
}

# 정책 문서 statement 밖의 rds-db 동작은 Resource 를 대조할 수 없어 위반이다.
test_r10_db_action_outside_statement if {
	one_hit([locals_file(media_host_path, "rds-db:connect")], "actions 에만")
}

# ARN 속 서비스 이름(`:rds-db:`)은 동작이 아니다.
test_r10_rds_db_arn_is_not_action if {
	files := [locals_file(media_root_path, "arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/media_indexer")]
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

# 5-3 교체 모델: 떠 있는 상자의 AMI · user_data 는 바꾸지 않는다.
instance_file(ignore) := resource_file("modules/media_host/main.tf", "aws_instance", {
	"ami": "${data.aws_ssm_parameter.ami.insecure_value}",
	"lifecycle": [{"ignore_changes": ignore}],
})

test_r10_instance_ignores_ami_and_user_data if {
	count(role_hits([instance_file(["${ami}", "${user_data_base64}"])])) == 0
}

test_r10_instance_missing_ignore_changes if {
	every ignore in [["${ami}"], ["${user_data_base64}"], [], "${var.ignored}"] {
		one_hit([instance_file(ignore)], "ignore_changes")
	}
}

test_r10_instance_other_dir_ignored if {
	file := resource_file(media_root_path, "aws_instance", {"ami": "ami-x"})
	count(role_hits([file])) == 0
}
