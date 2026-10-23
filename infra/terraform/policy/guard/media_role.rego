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
#    rds-db:connect 를 덮으면(`rds-db:*` · `*` 포함, 대소문자 무관) Resource 가
#    모두 dbuser ARN 이어야 한다(`*` · 변수 통째 · resources 없음은 위반).
# ③ rds-db 동작은 그 statement 의 actions 에만 적는다 — jsonencode 인라인
#    정책 · dynamic 블록에 있으면 Resource 를 대조할 수 없어 위반이다. 설명
#    문자열(description)은 문서라 보지 않는다.
# 응답기 거부 목록 그릇(modules/media_host 의 aws_ssm_parameter)은 lifecycle
# ignore_changes 에 value 가 있어야 한다 — 없으면 다음 apply 가 운영자가 고친
# 목록을 초기값으로 덮어 거부가 조용히 풀린다. prevent_destroy 는
# prevent_destroy.rego 의 보호 대상(R10 줄)이 본다.
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

as_list(value) := value if is_array(value)

as_list(value) := [value] if is_string(value)

covers_db_connect(action) if glob.match(lower(action), [], "rds-db:connect")

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

db_statements contains item if {
	some item in policy_statements
	some action in as_list(item.statement.actions)
	covers_db_connect(action)
}

deny contains msg if {
	some item in db_statements
	not "resources" in object.keys(item.statement)
	msg := sprintf(
		"R10 %s: %s 의 rds-db:connect 문에 resources 없음 — dbuser ARN 으로 좁힌다",
		[item.decl.path, label(item.decl)],
	)
}

deny contains msg if {
	some item in db_statements
	some resource in as_list(item.statement.resources)
	not contains(resource, ":dbuser:")
	msg := sprintf(
		"R10 %s: %s 의 rds-db:connect Resource %q — dbuser ARN 만",
		[item.decl.path, label(item.decl), resource],
	)
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
	msg := sprintf(
		"R10 %s: rds-db 동작은 aws_iam_policy_document statement 의 actions 에만(Resource 대조)",
		[item.file],
	)
}

ignores_value(body) if {
	some lifecycle in body.lifecycle
	"${value}" in lifecycle.ignore_changes
}

deny contains msg if {
	some decl in declarations
	in_dir(decl.path, "modules/media_host")
	decl.kind == "resource"
	decl.type == "aws_ssm_parameter"
	not ignores_value(decl.body)
	msg := sprintf(
		"R10 %s: %s 의 lifecycle ignore_changes 에 value 없음(운영자가 고친 거부 목록을 apply 가 덮음)",
		[decl.path, label(decl)],
	)
}
