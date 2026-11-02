package main

# R12 시험 입력. 공개 존 소유 루트(nonprod/dns-legacy) 바로 아래 파일이다.
r12_main := "nonprod/dns-legacy/main.tf"

r12_kept := {"name": "example.com", "lifecycle": [{"prevent_destroy": true}]}

# 공개 존 루트 파일 하나. zones = {자원 이름: 본문}.
r12_zone_file(zones) := tf(r12_main, {"resource": {"aws_route53_zone": {name: [body] |
	some name, body in zones
}}})

# 표지(guard.sh 가 --data 로 넘기는 mode)를 정해 돌린 R12 위반.
r12_hits_in(mode, files) := msgs if {
	msgs := hits("R12", files) with data.mode as mode
}

# 공개 존 루트가 없는 트리(다른 규칙의 파일만).
r12_unrelated := [resource_file(other, "aws_s3_bucket", {"bucket": "example"})]

r12_absent_prefix := "R12 nonprod/dns-legacy: .tf 없음"

r12_undeclared := `"legacy" 선언 없음`

# 사례 1: 운영 표지면 공개 존 루트에 .tf 가 없는 것이 위반이다.
test_r12_operational_requires_root_files if {
	msgs := r12_hits_in("operational", r12_unrelated)
	count(msgs) == 1
	some msg in msgs
	startswith(msg, r12_absent_prefix)
}

# 사례 11: 표지가 없으면(conftest verify 는 data 를 넘기지 않는다) 운영 트리다.
test_r12_missing_marker_is_operational if {
	msgs := hits("R12", r12_unrelated)
	count(msgs) == 1
	some msg in msgs
	startswith(msg, r12_absent_prefix)
}

# 사례 11: 표지가 정확히 "fixture" 가 아니면 운영 트리다.
test_r12_unknown_marker_is_operational if {
	every mode in {"Fixture", "fixtures", "", "test", true} {
		count(r12_hits_in(mode, r12_unrelated)) == 1
	}
}

# 하위 디렉터리 · 이웃 디렉터리의 .tf 는 루트의 파일이 아니다(Terraform 은
# 루트 바로 아래 파일만 읽는다).
test_r12_files_outside_root_do_not_count if {
	every path in {"nonprod/dns-legacy/sub/main.tf", "nonprod/dns-legacy2/main.tf"} {
		files := [tf(path, {"resource": {"aws_route53_zone": {"legacy": [r12_kept]}}})]
		msgs := r12_hits_in("operational", files)
		count(msgs) == 1
		some msg in msgs
		startswith(msg, r12_absent_prefix)
	}
}

# 사례 2: 존 주소가 legacy 가 아니면 위반이다.
test_r12_renamed_zone if {
	msgs := r12_hits_in("operational", [r12_zone_file({"public": r12_kept})])
	count(msgs) == 1
	some msg in msgs
	startswith(msg, "R12 nonprod/dns-legacy: ")
	contains(msg, r12_undeclared)
}

# 사례 2: moved 블록이 있어도 같다.
test_r12_renamed_zone_with_moved if {
	moved := {"moved": [{
		"from": "${aws_route53_zone.legacy}",
		"to": "${aws_route53_zone.public}",
	}]}
	files := [r12_zone_file({"public": r12_kept}), tf("nonprod/dns-legacy/moved.tf", moved)]
	msgs := r12_hits_in("operational", files)
	count(msgs) == 1
	some msg in msgs
	contains(msg, r12_undeclared)
}

# 같은 이름의 data source 는 존 선언이 아니다.
test_r12_data_source_is_not_declaration if {
	body := {"name": "example.com"}
	files := [tf(r12_main, {"data": {"aws_route53_zone": {"legacy": [body]}}})]
	msgs := r12_hits_in("operational", files)
	count(msgs) == 1
	some msg in msgs
	contains(msg, r12_undeclared)
}

# 사례 5: fixture 표지면 ① · ② 의 부재를 건너뛴다.
test_r12_fixture_skips_absence if {
	count(r12_hits_in("fixture", r12_unrelated)) == 0
	count(r12_hits_in("fixture", [r12_zone_file({"public": r12_kept})])) == 0
}

# 사례 3: 식 · 문자열 · false · lifecycle 없음은 두 모드 모두 위반이다.
test_r12_not_literal_true if {
	bodies := [
		{"lifecycle": [{"prevent_destroy": "${var.keep}"}]},
		{"lifecycle": [{"prevent_destroy": "true"}]},
		{"lifecycle": [{"prevent_destroy": "${true != true}"}]},
		{"lifecycle": [{"prevent_destroy": false}]},
		{"lifecycle": [{"ignore_changes": ["${tags}"]}]},
		{"name": "example.com"},
	]
	every body in bodies {
		every mode in {"fixture", "operational"} {
			msgs := r12_hits_in(mode, [r12_zone_file({"legacy": body})])
			count(msgs) == 1
			some msg in msgs
			startswith(msg, "R12 nonprod/dns-legacy/main.tf: ")
			contains(msg, "prevent_destroy")
		}
	}
}

# 사례 4: 같은 루트의 다른 존에도 리터럴 true 가 있어야 한다.
test_r12_other_zone_unprotected if {
	zones := {"legacy": r12_kept, "extra": {"name": "sub.example.com"}}
	every mode in {"fixture", "operational"} {
		msgs := r12_hits_in(mode, [r12_zone_file(zones)])
		count(msgs) == 1
		some msg in msgs
		contains(msg, `"extra"`)
	}
}

# 사례 6: legacy 주소 · 리터럴 true 면 두 모드 모두 통과한다.
test_r12_protected_legacy_clean if {
	every mode in {"fixture", "operational"} {
		count(r12_hits_in(mode, [r12_zone_file({"legacy": r12_kept})])) == 0
	}
}

# 존이 어느 파일에 있든 루트 바로 아래면 같은 루트다.
test_r12_zone_in_other_root_file if {
	files := [
		tf("nonprod/dns-legacy/variables.tf", {"variable": {"zone_name": [{}]}}),
		tf("nonprod/dns-legacy/zone.tf", {"resource": {"aws_route53_zone": {"legacy": [r12_kept]}}}),
	]
	count(r12_hits_in("operational", files)) == 0
}

# 다른 루트의 존은 R12 대상이 아니다.
test_r12_zone_in_other_root_ignored if {
	files := [
		r12_zone_file({"legacy": r12_kept}),
		resource_file("prod/certs/main.tf", "aws_route53_zone", {"name": "example.com"}),
	]
	count(r12_hits_in("operational", files)) == 0
}
