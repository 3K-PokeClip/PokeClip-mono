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
	attachment_file("modules/media_host/main.tf", object.union(managed_locals, db_users_locals), "managed", managed_body),
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

# 대상 경로의 자원 · data 는 지금 쓰는 유형만 — 관리형 정책 ARN 을 문자열로
# 적지 않는 길(정책 조회 · 고객 관리 정책 · 다른 연결 자원)과 다른 공급자 ·
# 다른 서비스로 역할에 정책을 붙이는 길(awscc · CloudFormation 스택)을 막는다.
test_r10_type_outside_allowlist if {
	every file in [
		tf(media_root_path, {"data": {"aws_iam_policy": {"admin": [{"name": "AdministratorAccess"}]}}}),
		resource_file(media_root_path, "aws_iam_policy", {"policy": "${data.aws_iam_policy_document.media.json}"}),
		resource_file(media_root_path, "aws_iam_policy_attachment", {"policy_arn": "${var.arn}"}),
		resource_file(media_root_path, "aws_iam_role_policy_attachments_exclusive", {"policy_arns": "${var.arns}"}),
		resource_file(media_root_path, "awscc_iam_role_policy", {
			"role_name": "pokeclip-prod-media",
			"policy_name": "x",
			"policy_document": "${jsonencode({Statement = [{Effect = \"Allow\", Action = \"*\", Resource = \"*\"}]})}",
		}),
		resource_file(media_host_path, "aws_cloudformation_stack", {
			"name": "x",
			"template_body": "${jsonencode({Resources = {P = {Type = \"AWS::IAM::RolePolicy\"}}})}",
		}),
		resource_file(media_host_path, "terraform_data", {"input": "x"}),
		tf(media_host_path, {"data": {"aws_secretsmanager_secret_version": {"x": [{"secret_id": "x"}]}}}),
		tf(media_root_path, {"data": {"external": {"x": [{"program": ["sh"]}]}}}),
	] {
		one_hit([file], "유형 허용 목록")
	}
}

# 두 경로가 지금 쓰는 자원 · data 유형은 유형 허용 목록에 걸리지 않는다(다른 R10
# 검사는 이 시험의 대상이 아니다).
test_r10_type_allowlist_accepts_used_types if {
	resources := [
		"aws_iam_role", "aws_iam_instance_profile", "aws_iam_role_policy",
		"aws_iam_role_policy_attachment", "aws_security_group",
		"aws_vpc_security_group_ingress_rule", "aws_vpc_security_group_egress_rule",
		"aws_instance", "aws_lb_target_group_attachment", "aws_ssm_parameter",
	]
	data_sources := [
		"aws_iam_policy_document", "aws_caller_identity", "aws_region",
		"aws_partition", "aws_ssm_parameter", "aws_ec2_instance_type",
		"terraform_remote_state",
	]
	files := array.concat(
		[resource_file(media_root_path, type, {}) | some type in resources],
		[tf(media_host_path, {"data": {type: {"this": [{}]}}}) | some type in data_sources],
	)
	count({msg | some msg in role_hits(files); contains(msg, "유형 허용 목록")}) == 0
}

test_r10_type_other_dir_ignored if {
	files := [resource_file("modules/ecs_service/main.tf", "aws_cloudformation_stack", {"name": "x"})]
	count(role_hits(files)) == 0
}

# 최상위 블록은 Terraform 설정이 쓰는 종류만 — ephemeral · action 은 자원 · data
# 유형 허용 목록 밖에서 공급자 동작을 부르고, check 는 그 안에 data 를 숨긴다.
# 나머지(import · moved · removed)도 지금 쓰지 않아 막는다.
test_r10_block_kind_outside_allowlist if {
	every file in [
		tf(media_host_path, {"ephemeral": {"aws_lambda_invocation": {"x": [{"function_name": "f"}]}}}),
		tf(media_host_path, {"action": {"aws_lambda_invoke": {"x": [{"config": [{"function_name": "f"}]}]}}}),
		tf(media_root_path, {"check": {"c": [{"data": {"aws_iam_policy": {"p": [{"name": "x"}]}}}]}}),
		tf(media_root_path, {"import": [{"to": "${aws_iam_role.b}", "id": "x"}]}),
		tf(media_root_path, {"moved": [{"from": "${aws_iam_role.a}", "to": "${aws_iam_role.b}"}]}),
		tf(media_root_path, {"removed": [{"from": "${aws_iam_role.c}"}]}),
	] {
		one_hit([file], "최상위 블록")
	}
}

test_r10_block_kind_allowlist_accepts_used_kinds if {
	files := [tf(media_root_path, {
		"terraform": [{"required_version": ">= 1.10"}],
		"provider": {"aws": [{"region": "${var.region}"}]},
		"variable": {"region": [{"type": "${string}"}]},
		"output": {"x": [{"value": "${var.region}"}]},
		"locals": [{"x": "y"}],
	})]
	count(role_hits(files)) == 0
}

# module 은 prod/media 가 ../../modules/media_host 를 부르는 것만 — 다른 모듈은
# 이 검사 밖의 자원으로 역할에 정책을 붙일 수 있다.
test_r10_module_source_outside_allowlist if {
	every file in [
		tf(media_root_path, {"module": {"extra": [{"source": "../../modules/ecs_service"}]}}),
		tf(media_root_path, {"module": {"extra": [{"source": "git::https://example.com/m.git"}]}}),
		tf(media_root_path, {"module": {"extra": [{"source": "terraform-aws-modules/iam/aws", "version": "6.0.0"}]}}),
		tf(media_root_path, {"module": {"extra": [{"version": "1.0.0"}]}}),
		tf(media_host_path, {"module": {"self": [{"source": "../../modules/media_host"}]}}),
	] {
		one_hit([file], "module")
	}
}

test_r10_module_media_host_accepted if {
	files := [tf(media_root_path, {"module": {"media_host": [{
		"source": "../../modules/media_host",
		"rds_resource_id": "${var.rds_resource_id}",
	}]}})]
	count(role_hits(files)) == 0
}

# 공급자는 hashicorp/aws 하나 — 다른 공급자(awscc 등)를 들이는 길을 막는다.
test_r10_provider_outside_aws if {
	every file in [
		tf(media_root_path, {"provider": {"awscc": [{"region": "${var.region}"}]}}),
		tf(media_host_path, {"terraform": [{"required_providers": [{"awscc": {"source": "hashicorp/awscc"}}]}]}),
		tf(media_host_path, {"terraform": [{"required_providers": [{"aws": {"source": "hashicorp/awscc"}}]}]}),
		tf(media_host_path, {"terraform": [{"required_providers": [{"aws": {"source": "example.com/evil/aws"}}]}]}),
		tf(media_host_path, {"terraform": [{"required_providers": [{"aws": {"version": ">= 6.19.0"}}]}]}),
		tf(media_host_path, {"terraform": [{"required_providers": [{"aws": "~> 6.0"}]}]}),
	] {
		one_hit([file], "공급자")
	}
}

test_r10_provider_aws_accepted if {
	files := [
		tf(media_root_path, {"provider": {"aws": [{"region": "${var.region}"}, {"alias": "use1", "region": "us-east-1"}]}}),
		tf("modules/media_host/versions.tf", {"terraform": [{
			"required_version": ">= 1.10",
			"required_providers": [{"aws": {"source": "hashicorp/aws", "version": ">= 6.19.0"}}],
		}]}),
	]
	count(role_hits(files)) == 0
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

# IAM 자원 · 정책 문서 · statement 는 허용 속성만 — 목록 밖 키(dynamic 포함)는
# 권한을 대조 밖으로 들여온다(역할 inline_policy · managed_policy_arns ·
# dynamic "inline_policy", 문서 합치기 · dynamic, statement 의 not_* · principals).
role_body := {
	"name": "pokeclip-prod-media",
	"path": "/pokeclip/prod/",
	"assume_role_policy": "${jsonencode({Statement = [{Action = \"sts:AssumeRole\"}]})}",
}

test_r10_iam_attribute_outside_allowlist if {
	every file in [
		resource_file(media_host_path, "aws_iam_role", object.union(role_body, {"inline_policy": [{"name": "x", "policy": "${data.aws_iam_policy_document.media.json}"}]})),
		resource_file(media_host_path, "aws_iam_role", object.union(role_body, {"managed_policy_arns": "${var.arns}"})),
		resource_file(media_host_path, "aws_iam_role", object.union(role_body, {"permissions_boundary": "${var.boundary}"})),
		resource_file(media_host_path, "aws_iam_role", object.union(role_body, {"dynamic": {"inline_policy": [{
			"for_each": "${var.x}",
			"content": [{"policy": "${jsonencode({Action = \"*\", Resource = \"*\"})}"}],
		}]}})),
		resource_file(media_host_path, "aws_iam_instance_profile", {"name": "x", "role": "x", "dynamic": {"tags": [{}]}}),
		resource_file(media_host_path, "aws_iam_role_policy", {"role": "x", "policy": "${data.aws_iam_policy_document.media.json}", "policy_arn": "${var.arn}"}),
		attachment_file(media_host_path, managed_locals, "managed", object.union(managed_body, {"policy_arns": "${var.arns}"})),
		tf(media_host_path, {"data": {"aws_iam_policy_document": {"media": [{"source_policy_documents": ["${jsonencode({})}"]}]}}}),
		tf(media_host_path, {"data": {"aws_iam_policy_document": {"media": [{"override_policy_documents": ["${var.extra}"]}]}}}),
		tf(media_host_path, {"data": {"aws_iam_policy_document": {"media": [{"dynamic": {"statement": [{"for_each": "${var.x}", "content": [{"actions": ["s3:GetObject"], "resources": ["*"]}]}]}}]}}}),
		policy_document_file(media_host_path, [{"not_actions": ["iam:PassRole"], "resources": ["*"]}]),
		policy_document_file(media_host_path, [{"actions": ["s3:GetObject"], "not_resources": ["arn:aws:s3:::x/*"]}]),
		policy_document_file(media_host_path, [{"actions": ["s3:GetObject"], "resources": ["*"], "principals": [{"type": "AWS", "identifiers": ["*"]}]}]),
		policy_document_file(media_host_path, [{"actions": ["s3:GetObject"], "resources": ["*"], "dynamic": {"condition": [{}]}}]),
	] {
		one_hit([file], "허용 속성")
	}
}

# 실제 모듈 · ok 픽스처가 쓰는 키와 메타 인자 · 무해한 키는 통과한다.
test_r10_iam_attribute_allowlist_accepts_used_keys if {
	files := [
		resource_file(media_host_path, "aws_iam_role", object.union(role_body, {"description": "x", "tags": {"a": "b"}, "lifecycle": [{}]})),
		resource_file("modules/media_host/profile.tf", "aws_iam_instance_profile", {"name": "x", "path": "/pokeclip/prod/", "role": "x", "tags": {}}),
		resource_file("modules/media_host/policy.tf", "aws_iam_role_policy", {"name": "x", "role": "x", "policy": "${data.aws_iam_policy_document.media.json}"}),
		attachment_file("modules/media_host/main.tf", managed_locals, "managed", object.union(managed_body, {"depends_on": ["${aws_iam_role.media}"]})),
		policy_document_file("modules/media_host/doc.tf", [{"sid": "S", "effect": "Allow", "actions": ["s3:GetObject"], "resources": ["*"], "condition": [{"test": "StringEquals", "variable": "aws:x", "values": ["y"]}]}]),
	]
	count(role_hits(files)) == 0
}

# 권한 정책은 정책 문서 json 으로만 붙인다. 신뢰 정책(assume_role_policy)은 예외.
test_r10_inline_policy_not_from_document if {
	every file in [
		resource_file(media_host_path, "aws_iam_role_policy", {
			"role": "${aws_iam_role.this.id}",
			"policy": "${jsonencode({Statement = [{Effect = \"Allow\", Action = \"s3:*\", Resource = \"*\"}]})}",
		}),
		resource_file(media_host_path, "aws_iam_role_policy", {"role": "${aws_iam_role.this.id}", "policy": "${var.policy}"}),
	] {
		one_hit([file], "인라인")
	}
}

# 어느 자리의 문자열이든 dbuser 뒤 와일드카드는 위반이다.
test_r10_dbuser_wildcards if {
	every arn in [
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:*/*",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/*",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:*/media_indexer",
		"arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/media_?",
	] {
		one_hit([locals_file(media_host_path, arn)], "와일드카드")
	}
}

# rds-db:connect 문과 그 DB 사용자 지역값을 함께 든 파일.
db_users_locals := {"db_users": ["media_indexer", "media_janitor"]}

db_file(locals, resources) := tf(media_host_path, {
	"locals": [locals],
	"data": {"aws_iam_policy_document": {"media": [{"statement": [db_statement(["rds-db:connect"], resources)]}]}},
})

test_r10_db_statement_accepts_db_users_loop if {
	every resources in [
		db_users_resources,
		"${[\n      for user in local.db_users :\n      \"arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.rds_resource_id}/${user}\"\n    ]}",
	] {
		count(role_hits([db_file(db_users_locals, resources)])) == 0
	}
}

# rds-db:connect 의 Resource 는 local.db_users 를 도는 꼴만 — 목록 리터럴 · 변수 ·
# 다른 목록 · dbuser 자리의 다른 보간 · resources 없음은 위반이다.
test_r10_db_statement_resource_not_db_users_loop if {
	every resources in [
		["*"],
		"${var.db_resource_arns}",
		["arn:aws:rds-db:ap-northeast-2:${data.aws_caller_identity.current.account_id}:dbuser:${var.rds_resource_id}/media_indexer"],
		"${[for u in var.db_users : \"arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.rds_resource_id}/${u}\"]}",
		"${[for u in local.db_users : \"arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.rds_resource_id}/${var.u}\"]}",
		"${[for u in local.db_users : \"arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.cluster}/${u}\"]}",
		"${[for u in local.db_users : \"arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.rds_resource_id}/${u}x\"]}",
		"${concat([for u in local.db_users : \"arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.rds_resource_id}/${u}\"], var.extra)}",
	] {
		one_hit([db_file(db_users_locals, resources)], "local.db_users")
	}
}

test_r10_db_statement_without_resources if {
	statement := {"actions": ["rds-db:connect"]}
	one_hit(
		[tf(media_host_path, {
			"locals": [db_users_locals],
			"data": {"aws_iam_policy_document": {"media": [{"statement": [statement]}]}},
		})],
		"local.db_users",
	)
}

# 동작 이름은 대소문자를 가리지 않는다 — `rds-db:Connect` 도 rds-db:connect 문으로
# 보고 Resource 를 대조한다(covers_db_connect 의 lower).
db_connect_mixed_case_file(resources) := tf(media_host_path, {
	"locals": [db_users_locals],
	"data": {"aws_iam_policy_document": {"media": [{"statement": [db_statement(["rds-db:Connect"], resources)]}]}},
})

test_r10_db_statement_mixed_case_action_checks_resource if {
	one_hit([db_connect_mixed_case_file(["*"])], "local.db_users")
}

test_r10_db_statement_mixed_case_action_accepts_db_users_loop if {
	count(role_hits([db_connect_mixed_case_file(db_users_resources)])) == 0
}

# DB 사용자 지역값은 두 이름 리터럴 목록 정확히.
test_r10_db_users_not_two_literal_names if {
	every locals in [
		{"db_users": ["*"]},
		{"db_users": ["media_indexer"]},
		{"db_users": ["media_indexer", "media_janitor", "admin"]},
		{"db_users": "${var.db_users}"},
		{"db_users": ["media_indexer", "${var.u}"]},
		{"other": "x"},
	] {
		one_hit([db_file(locals, db_users_resources)], "db_users")
	}
}

# 정책 문서 statement 의 actions 원소는 `서비스:동작` 꼴(허용 목록)만 —
# 와일드카드 · 템플릿 지시자 · 보간 · 공백 · 식은 rds-db:connect 를 덮는지 대조할
# 수 없다. 메시지는 하나다(지시자 규칙 · rds-db 문 규칙과 겹치지 않는다).
test_r10_statement_actions_outside_allowlist if {
	every actions in [
		["*"],
		["rds-db:*"],
		["s3:Get*"],
		["rds-db:connec?"],
		["RDS-DB:Connect"],
		["%{if true}rds-db:connect%{endif}"],
		["*%{if false}x%{endif}"],
		["%{for a in var.acts}${a}%{endfor}"],
		["s3: GetObject"],
		["s3:GetObject "],
		[""],
		["${var.a}"],
		["${format(\"%s:%s\", \"rds-db\", \"connect\")}"],
		"${local.acts}",
		"${var.acts}",
		"${concat([\"s3:GetObject\"], var.more)}",
		"${toset([\"*\"])}",
	] {
		one_hit(
			[tf(media_host_path, {
				"locals": [db_users_locals],
				"data": {"aws_iam_policy_document": {"media": [{"statement": [db_statement(actions, ["*"])]}]}},
			})],
			"서비스:동작",
		)
	}
}

test_r10_statement_actions_allowlist_accepts_service_action if {
	statement := db_statement(["ecr:GetAuthorizationToken", "s3:PutObject", "rds-db:connect"], db_users_resources)
	count(role_hits([tf(media_host_path, {
		"locals": [db_users_locals],
		"data": {"aws_iam_policy_document": {"media": [{"statement": [statement]}]}},
	})])) == 0
}

# 대상 경로의 어느 문자열이든(설명 포함) 템플릿 지시자 `%{` 는 위반이다 — dbuser ·
# rds-db 문자열 대조를 지시자로 비켜 가지 못하게.
test_r10_template_directive_any_string if {
	every file in [
		locals_file(media_host_path, "%{if true}rds-db:connect%{endif}"),
		locals_file(media_host_path, "arn:aws:rds-db:ap-northeast-2:${local.account_id}:dbuser:${var.rds_resource_id}/%{if true}*%{endif}"),
		tf(media_root_path, {"variable": {"x": [{"description": "%{for a in var.x}${a}%{endfor}"}]}}),
		tf(media_root_path, {"locals": [{"tags": {"%{if true}x%{endif}": "y"}}]}),
	] {
		one_hit([file], "%{")
	}
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
