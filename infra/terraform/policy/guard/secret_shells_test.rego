package main

shells_path := "modules/secret_shells/main.tf"

# names 입력과 그릇 자원만 든 비밀 그릇 모듈(통과하는 꼴).
shells_contents := {
	"variable": {"names": [{"type": "${set(string)}"}]},
	"resource": {"aws_secretsmanager_secret": {"this": [{
		"for_each": "${var.names}",
		"name": "${each.key}",
	}]}},
}

test_r6_names_only_clean if {
	clean([tf(shells_path, shells_contents)])
}

test_r6_secret_policy_resource if {
	files := [
		tf(shells_path, shells_contents),
		resource_file("modules/secret_shells/policy.tf", "aws_secretsmanager_secret_policy", {
			"secret_arn": "${aws_secretsmanager_secret.this.arn}",
			"policy": "{}",
		}),
	]
	count(hits("R6", files)) == 1
}

# conftest 는 count 를 펼치지 않는다: count = 0 이어도 선언이 있으면 위반이다.
test_r6_secret_policy_count_zero if {
	files := [resource_file(shells_path, "aws_secretsmanager_secret_policy", {"count": 0})]
	count(hits("R6", files)) == 1
}

# kty 61차: 그릇 자원의 인라인 policy 인자도 리소스 정책이다.
test_r6_inline_policy if {
	body := {"name": "${each.key}", "policy": "${jsonencode({})}"}
	files := [resource_file(shells_path, "aws_secretsmanager_secret", body)]
	count(hits("R6", files)) == 1
}

# 값이 무엇이든(null · 빈 문자열) policy 인자를 쓰면 위반이다(엄격한 쪽).
test_r6_inline_policy_any_value if {
	every value in [null, "", false] {
		body := {"name": "example", "policy": value}
		count(hits("R6", [resource_file(shells_path, "aws_secretsmanager_secret", body)])) == 1
	}
}

test_r6_extra_variable if {
	contents := object.union(shells_contents, {"variable": {
		"names": [{"type": "${set(string)}"}],
		"reader_role_arns": [{"type": "${list(string)}"}],
	}})
	msgs := hits("R6", [tf(shells_path, contents)])
	count(msgs) == 1
	some msg in msgs
	contains(msg, "reader_role_arns")
}

# 다른 파일에 둔 변수도 같은 모듈의 입력이다.
test_r6_extra_variable_in_other_file if {
	files := [
		tf(shells_path, shells_contents),
		tf("modules/secret_shells/variables.tf", {"variable": {"recovery_window": [{}]}}),
	]
	count(hits("R6", files)) == 1
}

test_r6_message_has_path if {
	files := [resource_file(shells_path, "aws_secretsmanager_secret_policy", {})]
	some msg in hits("R6", files)
	startswith(msg, "R6 modules/secret_shells/main.tf: ")
}

# 모듈 밖(루트 · 다른 모듈)의 리소스 정책 · 변수 · 인라인 policy 는 R6 대상이
# 아니다 — 읽기 정책은 읽는 쪽 루트의 몫이다.
test_r6_outside_module_ignored if {
	files := [
		resource_file(other, "aws_secretsmanager_secret_policy", {"policy": "{}"}),
		resource_file("modules/secret_shells_extra/main.tf", "aws_secretsmanager_secret", {
			"name": "example",
			"policy": "{}",
		}),
		tf("modules/other/variables.tf", {"variable": {"reader_role_arns": [{}]}}),
	]
	clean(files)
}
