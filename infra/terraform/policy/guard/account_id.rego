# R13: 계정 비의존(설계 3-1 「계정 비의존 규약」). 계정 ID 는
# data.aws_caller_identity 로 얻고 코드 리터럴에 두지 않는다 — 리스 만료 뒤
# 다른 계정에서 같은 코드로 다시 올리기 위해서다(설계 9-2).
#
# 문자열 · 숫자 리터럴(맵 키 포함) 안에서 앞뒤가 숫자가 아닌 계정 ID 꼴
# 숫자열(12자리 · 4-4-4 대시 표기 — ARN 속 포함)과, 이름을 소문자로 바꾸고
# - · _ 를 뺐을 때 accountid 가 드는 variable 을 막는다. 식은 문자열로
# 남으므로 식 속 리터럴도 잡힌다. 위반 메시지의 그 숫자열은 가린다 — 실제
# 계정 ID 가 로그에 남지 않게.
package main

account_id_patterns := [
	`(^|[^0-9])[0-9]{12}([^0-9]|$)`,
	`(^|[^0-9])[0-9]{4}-[0-9]{4}-[0-9]{4}([^0-9]|$)`,
]

account_id_like(value) if {
	is_string(value)
	some pattern in account_id_patterns
	regex.match(pattern, value)
}

account_id_like(value) if {
	is_number(value)
	regex.match(`^[0-9]{12}$`, format_int(abs(value), 10))
}

# 계정 ID 꼴 리터럴의 위치. 값과 맵 키(경로의 마지막 토막)를 함께 본다.
account_id_paths(contents) := {path |
	walk(contents, [path, value])
	account_id_like(value)
} | {path |
	walk(contents, [path, _])
	count(path) > 0
	account_id_like(path[count(path) - 1])
}

hide_digits(text) := regex.replace(
	regex.replace(text, `[0-9]{12,}`, "<숫자열>"),
	`[0-9]{4}-[0-9]{4}-[0-9]{4}`,
	"<숫자열>",
)

# 변수 이름 비교용: 소문자로 바꾸고 - · _ 를 뺀다(accountId · account-id).
squashed_name(name) := lower(replace(replace(name, "-", ""), "_", ""))

deny contains msg if {
	some file in input
	some path in account_id_paths(file.contents)
	location := concat(".", [sprintf("%v", [step]) | some step in path])
	msg := hide_digits(sprintf(
		"R13 %s: %s 에 계정 ID 꼴 숫자열 — data.aws_caller_identity 로",
		[file.path, location],
	))
}

deny contains msg if {
	some file in input
	some name, _ in file.contents.variable
	contains(squashed_name(name), "accountid")
	msg := hide_digits(sprintf(
		"R13 %s: variable %q — 이름에 account id 금지(data.aws_caller_identity 로)",
		[file.path, name],
	))
}
