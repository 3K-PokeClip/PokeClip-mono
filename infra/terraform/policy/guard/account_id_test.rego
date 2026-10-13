package main

# R13 시험의 계정 ID 는 시험용 가짜 값 123456789012 다.

# 값 하나를 locals 에 둔 파일.
r13_local(value) := tf(other, {"locals": [{"value": value}]})

# 사례 7: ARN 속 계정 ID.
test_r13_arn_literal if {
	statement := {"resources": ["arn:aws:iam::123456789012:role/x"]}
	files := [tf(other, {"data": {"aws_iam_policy_document": {"this": [{"statement": [statement]}]}}})]
	count(hits("R13", files)) == 1
}

# 사례 7: 문자열 · 숫자 리터럴.
test_r13_string_and_number_literals if {
	every value in ["123456789012", 123456789012, -123456789012] {
		count(hits("R13", [r13_local(value)])) == 1
	}
}

# 식은 문자열로 남으므로 식 속 리터럴도 잡힌다.
test_r13_literal_inside_expression if {
	value := `${jsonencode({Resource = "arn:aws:iam::123456789012:root"})}`
	count(hits("R13", [r13_local(value)])) == 1
}

# 맵 키도 문자열 리터럴이다.
test_r13_map_key if {
	files := [tf(other, {"locals": [{"tags": {"123456789012": "example"}}]})]
	count(hits("R13", files)) == 1
}

# 사례 7: 11자리 · 13자리 숫자열은 계정 ID 꼴이 아니다.
test_r13_other_lengths_allowed if {
	values := [
		"12345678901",
		"1234567890123",
		12345678901,
		1234567890123,
		"arn:aws:iam::12345678901:role/x",
		"arn:aws:iam::1234567890123:role/x",
	]
	every value in values {
		count(hits("R13", [r13_local(value)])) == 0
	}
}

# 사례 7: data.aws_caller_identity 참조는 통과한다.
test_r13_caller_identity_allowed if {
	values := [
		"${data.aws_caller_identity.current.account_id}",
		"arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/x",
	]
	every value in values {
		count(hits("R13", [r13_local(value)])) == 0
	}
}

# 위반 메시지에는 숫자열 값을 적지 않는다(실제 계정 ID 가 로그에 남지 않게).
test_r13_message_hides_value if {
	files := [
		r13_local("arn:aws:iam::123456789012:role/x"),
		tf("other.tf", {"locals": [{"tags": {"123456789012": "example"}}]}),
	]
	msgs := hits("R13", files)
	count(msgs) == 2
	every msg in msgs {
		not contains(msg, "123456789012")
	}
	some msg in msgs
	startswith(msg, "R13 main.tf: ")
}

# 사례 8: 이름에 account_id 가 든 변수(대소문자 무관).
test_r13_account_id_variables if {
	variables := {"account_id": [{}], "prod_account_id": [{}], "Prod_Account_Id": [{}]}
	count(hits("R13", [tf(other, {"variable": variables})])) == 3
}

# 다른 이름의 변수와 locals 의 account_id 는 대상이 아니다.
test_r13_other_names_allowed if {
	files := [
		tf(other, {"variable": {"region": [{}], "account": [{}], "zone_id": [{}]}}),
		tf("locals.tf", {"locals": [{"account_id": "${data.aws_caller_identity.current.account_id}"}]}),
	]
	count(hits("R13", files)) == 0
}

# 변수 이름은 소문자로 바꾸고 - · _ 를 뺀 뒤 accountid 가 드는지 본다.
test_r13_account_id_variable_spellings if {
	variables := {
		"accountId": [{}],
		"account-id": [{}],
		"ACCOUNTID": [{}],
		"prod-Account_Id": [{}],
	}
	count(hits("R13", [tf(other, {"variable": variables})])) == 4
}

# 4-4-4 대시 표기도 계정 ID 꼴이다(문자열 · ARN · 맵 키).
test_r13_dashed_account_id if {
	every value in ["1234-5678-9012", "arn:aws:iam::1234-5678-9012:root"] {
		count(hits("R13", [r13_local(value)])) == 1
	}
	files := [tf(other, {"locals": [{"tags": {"1234-5678-9012": "example"}}]})]
	count(hits("R13", files)) == 1
}

# 다른 대시 묶음(자릿수가 다르거나 앞뒤에 숫자가 붙음)은 대상이 아니다.
test_r13_other_dashed_numbers_allowed if {
	values := [
		"123-4567-8901",
		"1234-5678-901",
		"12345-5678-9012",
		"1234-5678-90123",
		"2026-10-13",
	]
	every value in values {
		count(hits("R13", [r13_local(value)])) == 0
	}
}

# 메시지는 대시 표기도 가린다.
test_r13_message_hides_dashed_value if {
	files := [tf(other, {"locals": [{"tags": {"1234-5678-9012": "example"}}]})]
	msgs := hits("R13", files)
	count(msgs) == 1
	every msg in msgs {
		not contains(msg, "1234-5678-9012")
	}
}
