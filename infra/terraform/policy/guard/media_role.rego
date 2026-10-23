# R10: Media 인스턴스 역할(modules/media_host · prod/media)의 권한을 좁게
# 묶는다(계획 M9 c1).
#
# 관리형 정책은 AmazonSSMManagedInstanceCore 하나만 붙인다 — 대상 경로의 어느
# 문자열이든 `iam::aws:policy/<이름>` 의 이름이 그 리터럴이 아니면 위반이다
# (이름이 식이면 대조할 수 없어 위반).
#
# rds-db:connect 는 DB 사용자 둘(media_indexer · media_janitor)로만 연다.
# 식을 평가하지 않으므로 이렇게 본다.
# ① 어느 문자열이든 `dbuser:` 뒤에 IAM 와일드카드(`*` · `?`)가 있으면 위반.
# ② 정책 문서(data "aws_iam_policy_document")의 statement 중 동작이
#    rds-db:connect 인 문(대소문자 무관)의 Resource 는
#    `[for <u> in local.db_users : "arn:…:dbuser:${var.rds_resource_id}/${<u>}"]`
#    꼴만이다 — 파티션 · 리전 · 계정 자리만 참조 보간을 받고, 사용자 자리는 for
#    변수뿐이다. 그 경로의 local.db_users 는 리터럴 목록으로 정확히
#    ["media_indexer", "media_janitor"] 다.
# ③ statement 의 actions 는 `서비스:동작` 꼴(^[a-z0-9-]+:[A-Za-z0-9]+$) 리터럴
#    목록만이다(허용 목록 — 와일드카드 · 템플릿 지시자 · 보간 · 공백 · 식은
#    rds-db:connect 를 덮는지 대조할 수 없다). rds-db 동작은 그 actions 에만
#    적는다 — 지역값 · jsonencode 등 다른 자리에 있으면 위반이다. 이 동작 이름
#    검사만 설명 문자열(description)을 보지 않는다(관리형 정책 ARN · dbuser
#    와일드카드 · 템플릿 지시자 검사는 설명도 본다).
# ③-1 대상 경로의 어느 문자열이든 템플릿 지시자 `%{` 는 위반이다 — 위 문자열
#    대조를 지시자로 비켜 가지 못하게(actions 원소는 ③ 이 하나로 알린다).
#
# 위 문자열 대조를 비켜 가는 길은 꼴로 막는다(아래 「꼴」 절).
# ④ IAM 유형은 역할 · 인스턴스 프로필 · 역할 인라인 정책 · 관리형 정책 연결 ·
#    정책 문서만(정책 조회 data "aws_iam_policy" · 고객 관리 정책 금지).
# ⑤ 관리형 정책 연결은 주소 managed 하나 · for_each = local.managed_policy_arns ·
#    policy_arn = each.value 이고, 그 지역값은 키 · 값 모두 SSM Core 하나다.
# ⑥ IAM 자원 · 정책 문서 · statement 는 허용 속성만 쓴다(유형마다 정한 키 +
#    메타 인자). 목록 밖 키는 dynamic 을 포함해 위반 — 역할의 inline_policy ·
#    managed_policy_arns · permissions_boundary, 문서 합치기(source · override),
#    statement 의 not_actions · not_resources · principals 가 여기서 걸린다.
# ⑦ 역할 인라인 정책은 정책 문서 json 으로만 붙인다(jsonencode 등 금지). 신뢰
#    정책(assume_role_policy)은 예외다.
#
# lifecycle(modules/media_host): 응답기 거부 목록 그릇(aws_ssm_parameter)은
# ignore_changes 에 value — 없으면 다음 apply 가 운영자가 고친 목록을 초기값으로
# 덮어 거부가 조용히 풀린다(prevent_destroy 는 prevent_destroy.rego 의 R10 줄).
# 상자(aws_instance)는 ignore_changes 에 ami · user_data_base64 — 설계 5-3 은
# 떠 있는 상자를 두고 새 구성을 새 키로 띄운다.
# 대상 경로에 파일이 없는 트리(다른 규칙의 fixture)는 위반이 없다.
package main

media_role_dirs := {"modules/media_host", "prod/media"}

allowed_managed_policies := {"AmazonSSMManagedInstanceCore"}

# 대상 경로 파일의 문자열 하나하나(경로 = hcl2 JSON 안의 위치).
media_role_strings contains item if {
	some file in input
	some dir in media_role_dirs
	in_dir(file.path, dir)
	walk(file.contents, [path, value])
	is_string(value)
	item := {"file": file.path, "path": path, "value": value}
}

deny contains msg if {
	some item in media_role_strings
	some match in regex.find_all_string_submatch_n(`iam::aws:policy/([^"\s,]*)`, item.value, -1)
	not match[1] in allowed_managed_policies
	msg := sprintf(
		"R10 %s: 관리형 정책 %q — Media 역할에는 AmazonSSMManagedInstanceCore 만",
		[item.file, match[1]],
	)
}

deny contains msg if {
	some item in media_role_strings
	some match in regex.find_all_string_submatch_n(`dbuser:([^"\s,]*)`, item.value, -1)
	regex.match(`[*?]`, match[1])
	msg := sprintf(
		"R10 %s: rds-db:connect Resource 에 와일드카드 dbuser:%s — DB 사용자를 하나씩 적는다",
		[item.file, match[1]],
	)
}

# 와일드카드 동작은 ③ 의 허용 목록이 막으므로 rds-db:connect 문은 그 동작을
# 그대로 적은 문이다.
covers_db_connect(action) if lower(action) == "rds-db:connect"

# 정책 문서 statement 블록 하나하나.
policy_statements contains item if {
	some decl in declarations
	some dir in media_role_dirs
	in_dir(decl.path, dir)
	decl.kind == "data"
	decl.type == "aws_iam_policy_document"
	some statement in decl.body.statement
	item := {"decl": decl, "statement": statement}
}

# 허용 목록을 어긴 문은 ③ 이 이미 알리므로 rds-db 문으로 다시 보지 않는다.
db_statements contains item if {
	some item in policy_statements
	allowed_actions(item.statement.actions)
	some action in item.statement.actions
	covers_db_connect(action)
}

# rds-db:connect Resource 의 유일한 꼴(hcl2 가 남긴 for 식 문자열). 캡처 둘 =
# for 변수 · dbuser 사용자 자리 — 같아야 한다(RE2 에는 역참조가 없다).
db_users_loop_pattern := concat("", [
	`^\$\{\[\s*for\s+([a-z_][a-z0-9_]*)\s+in\s+local\.db_users\s*:\s*"arn:`,
	`(?:aws|\$\{[a-z0-9_.]+\}):rds-db:(?:[a-z0-9-]+|\$\{[a-z0-9_.]+\}):\$\{[a-z0-9_.]+\}:`,
	`dbuser:\$\{var\.rds_resource_id\}/\$\{([a-z_][a-z0-9_]*)\}"\s*\]\}$`,
])

db_users_loop(value) if {
	is_string(value)
	some match in regex.find_all_string_submatch_n(db_users_loop_pattern, value, 1)
	match[1] == match[2]
}

deny contains msg if {
	some item in db_statements
	not db_users_loop(object.get(item.statement, "resources", null))
	msg := sprintf(
		"R10 %s: %s 의 rds-db:connect Resource 는 [for <u> in local.db_users : \"…:dbuser:${var.rds_resource_id}/${<u>}\"] 꼴만",
		[item.decl.path, label(item.decl)],
	)
}

# rds-db:connect 문이 있는 경로마다 그 경로의 db_users 지역값.
media_db_users(dir) := [value |
	some file in input
	in_dir(file.path, dir)
	some block in file.contents.locals
	value := block.db_users
]

two_db_users(value) if {
	is_array(value)
	count(value) == 2
	{user | some user in value} == {"media_indexer", "media_janitor"}
}

deny contains msg if {
	some dir in media_role_dirs
	some item in db_statements
	in_dir(item.decl.path, dir)
	count(media_db_users(dir)) == 0
	msg := sprintf("R10 %s: db_users 지역값 없음 — rds-db:connect 의 대상", [dir])
}

deny contains msg if {
	some dir in media_role_dirs
	some item in db_statements
	in_dir(item.decl.path, dir)
	some value in media_db_users(dir)
	not two_db_users(value)
	msg := sprintf("R10 %s: db_users 는 [\"media_indexer\", \"media_janitor\"] 리터럴 목록만", [dir])
}

allowed_action_pattern := `^[a-z0-9-]+:[A-Za-z0-9]+$`

allowed_actions(value) if {
	is_array(value)
	every action in value {
		is_string(action)
		regex.match(allowed_action_pattern, action)
	}
}

deny contains msg if {
	some item in policy_statements
	"actions" in object.keys(item.statement)
	not allowed_actions(item.statement.actions)
	msg := sprintf(
		"R10 %s: %s 의 statement actions 는 `서비스:동작` 꼴 리터럴 목록만(허용 목록 — 와일드카드 · 지시자 · 식 금지)",
		[item.decl.path, label(item.decl)],
	)
}

template_directive_message(file) := sprintf("R10 %s: 템플릿 지시자(%%{) 금지 — 문자열 대조를 비켜 간다", [file])

deny contains msg if {
	some item in media_role_strings
	contains(item.value, "%{")
	not statement_actions_path(item.path)
	msg := template_directive_message(item.file)
}

# 맵 키도 문자열이다.
deny contains msg if {
	some file in input
	some dir in media_role_dirs
	in_dir(file.path, dir)
	walk(file.contents, [_, value])
	is_object(value)
	some key, _ in value
	contains(key, "%{")
	msg := template_directive_message(file.path)
}

# hcl2 JSON 에서 정책 문서 statement 의 actions 자리:
# data.aws_iam_policy_document.<이름>[i].statement[j].actions[k].
statement_actions_path(path) if {
	count(path) in {7, 8}
	path[0] == "data"
	path[1] == "aws_iam_policy_document"
	path[4] == "statement"
	path[6] == "actions"
}

# variable · output 등의 설명 문자열 — 문서라 동작 자리로 보지 않는다.
description_path(path) if path[count(path) - 1] == "description"

deny contains msg if {
	some item in media_role_strings
	regex.match(`(?i)(^|[^:\w-])rds-db:[a-z*?]`, item.value)
	not statement_actions_path(item.path)
	not description_path(item.path)
	not contains(item.value, "%{") # 지시자 규칙(③-1)이 알린다.
	msg := sprintf(
		"R10 %s: rds-db 동작은 aws_iam_policy_document statement 의 actions 에만(Resource 대조)",
		[item.file],
	)
}

# lifecycle ignore_changes 에 속성 attr(리터럴 목록 원소 "${attr}")이 있는지.
ignores(body, attr) if {
	some lifecycle in body.lifecycle
	sprintf("${%s}", [attr]) in lifecycle.ignore_changes
}

deny contains msg if {
	some decl in declarations
	in_dir(decl.path, "modules/media_host")
	decl.kind == "resource"
	decl.type == "aws_ssm_parameter"
	not ignores(decl.body, "value")
	msg := sprintf(
		"R10 %s: %s 의 lifecycle ignore_changes 에 value 없음(운영자가 고친 거부 목록을 apply 가 덮음)",
		[decl.path, label(decl)],
	)
}

deny contains msg if {
	some decl in declarations
	in_dir(decl.path, "modules/media_host")
	decl.kind == "resource"
	decl.type == "aws_instance"
	missing := [attr | some attr in ["ami", "user_data_base64"]; not ignores(decl.body, attr)]
	count(missing) > 0
	msg := sprintf(
		"R10 %s: %s 의 lifecycle ignore_changes 에 %s 없음(설계 5-3 — 새 구성은 새 키로)",
		[decl.path, label(decl), concat(" · ", missing)],
	)
}

# ---- 꼴: 문자열 대조를 비켜 가는 길 ----

media_role_decls contains decl if {
	some decl in declarations
	some dir in media_role_dirs
	in_dir(decl.path, dir)
}

allowed_iam_types := {
	"resource": {
		"aws_iam_role",
		"aws_iam_instance_profile",
		"aws_iam_role_policy",
		"aws_iam_role_policy_attachment",
	},
	"data": {"aws_iam_policy_document"},
}

deny contains msg if {
	some decl in media_role_decls
	startswith(decl.type, "aws_iam_")
	not decl.type in allowed_iam_types[decl.kind]
	msg := sprintf(
		"R10 %s: %s — IAM 유형은 역할 · 인스턴스 프로필 · 역할 인라인 정책 · 관리형 정책 연결 · 정책 문서만",
		[decl.path, label(decl)],
	)
}

managed_attachments contains decl if {
	some decl in media_role_decls
	decl.kind == "resource"
	decl.type == "aws_iam_role_policy_attachment"
}

deny contains msg if {
	some decl in managed_attachments
	decl.name != "managed"
	msg := sprintf("R10 %s: %s — 관리형 정책 연결 주소는 managed 하나만", [decl.path, label(decl)])
}

deny contains msg if {
	some decl in managed_attachments
	object.get(decl.body, "policy_arn", null) != "${each.value}"
	msg := sprintf("R10 %s: %s 의 policy_arn 은 each.value 만", [decl.path, label(decl)])
}

deny contains msg if {
	some decl in managed_attachments
	object.get(decl.body, "for_each", null) != "${local.managed_policy_arns}"
	msg := sprintf("R10 %s: %s 의 for_each 는 local.managed_policy_arns 만", [decl.path, label(decl)])
}

# 연결이 있는 대상 경로마다 그 경로의 managed_policy_arns 지역값.
managed_policy_locals(dir) := [value |
	some file in input
	in_dir(file.path, dir)
	some block in file.contents.locals
	value := block.managed_policy_arns
]

ssm_core_arn_pattern := `^arn:(aws|\$\{local\.partition\}|\$\{data\.aws_partition\.[a-z0-9_]+\.partition\}):iam::aws:policy/AmazonSSMManagedInstanceCore$`

ssm_core_only(value) if {
	is_object(value)
	object.keys(value) == {"AmazonSSMManagedInstanceCore"}
	regex.match(ssm_core_arn_pattern, value.AmazonSSMManagedInstanceCore)
}

deny contains msg if {
	some dir in media_role_dirs
	some decl in managed_attachments
	in_dir(decl.path, dir)
	count(managed_policy_locals(dir)) == 0
	msg := sprintf("R10 %s: managed_policy_arns 지역값 없음 — 관리형 정책 연결의 대상", [dir])
}

deny contains msg if {
	some dir in media_role_dirs
	some decl in managed_attachments
	in_dir(decl.path, dir)
	some value in managed_policy_locals(dir)
	not ssm_core_only(value)
	msg := sprintf(
		"R10 %s: managed_policy_arns 는 { AmazonSSMManagedInstanceCore = <그 ARN 리터럴> } 하나만",
		[dir],
	)
}

# 모든 자원 · data 가 쓸 수 있는 메타 인자.
meta_arguments := {"count", "for_each", "depends_on", "lifecycle", "provider"}

# 유형마다 쓸 수 있는 최상위 키(실제 모듈 · ok 픽스처가 쓰는 키와 무해한 것).
# 목록에 없는 키는 dynamic 을 포함해 위반이다.
allowed_iam_attributes := {
	"aws_iam_role": {
		"name", "name_prefix", "path", "assume_role_policy", "description",
		"max_session_duration", "force_detach_policies", "tags",
	},
	"aws_iam_instance_profile": {"name", "name_prefix", "path", "role", "tags"},
	"aws_iam_role_policy": {"name", "name_prefix", "role", "policy"},
	"aws_iam_role_policy_attachment": {"role", "policy_arn"},
	"aws_iam_policy_document": {"statement", "version"},
}

allowed_statement_attributes := {"sid", "effect", "actions", "resources", "condition"}

deny contains msg if {
	some decl in media_role_decls
	some allowed in [allowed_iam_attributes[decl.type]]
	some key, _ in decl.body
	not key in allowed
	not key in meta_arguments
	msg := sprintf("R10 %s: %s 의 %s — IAM 자원은 허용 속성만(dynamic 포함 금지)", [decl.path, label(decl), key])
}

deny contains msg if {
	some item in policy_statements
	some key, _ in item.statement
	not key in allowed_statement_attributes
	msg := sprintf(
		"R10 %s: %s 의 statement %s — 허용 속성만(sid · effect · actions · resources · condition)",
		[item.decl.path, label(item.decl), key],
	)
}

policy_document_json_pattern := `^\$\{data\.aws_iam_policy_document\.[A-Za-z0-9_-]+\.json\}$`

deny contains msg if {
	some decl in media_role_decls
	decl.kind == "resource"
	decl.type == "aws_iam_role_policy"
	not regex.match(policy_document_json_pattern, object.get(decl.body, "policy", ""))
	msg := sprintf("R10 %s: %s — 역할 인라인 정책은 정책 문서 json 으로만", [decl.path, label(decl)])
}
