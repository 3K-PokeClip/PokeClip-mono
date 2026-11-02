package main

kept := {"lifecycle": [{"prevent_destroy": true}]}

bucket_path := "modules/state_bucket/main.tf"

dev_path := "nonprod/dev/main.tf"

dev_resources(eip_body) := tf(dev_path, {"resource": {
	"aws_instance": {"dev": [kept]},
	"aws_eip": {"dev": [eip_body]},
	"aws_security_group": {"dev": [kept]},
}})

test_r4_protected_bucket_clean if {
	clean([resource_file(bucket_path, "aws_s3_bucket", kept)])
}

test_r4_missing_lifecycle if {
	files := [resource_file(bucket_path, "aws_s3_bucket", {"bucket": "example"})]
	count(hits("R4", files)) == 1
}

test_r4_lifecycle_without_prevent_destroy if {
	body := {"lifecycle": [{"ignore_changes": ["${tags}"]}]}
	count(hits("R4", [resource_file(bucket_path, "aws_s3_bucket", body)])) == 1
}

test_r4_false if {
	body := {"lifecycle": [{"prevent_destroy": false}]}
	count(hits("R4", [resource_file(bucket_path, "aws_s3_bucket", body)])) == 1
}

# 식은 평가하지 않는다: `true != true` 는 문자열 "${true != true}" 로 와서 위반이다.
test_r4_expression if {
	body := {"lifecycle": [{"prevent_destroy": "${true != true}"}]}
	count(hits("R4", [resource_file(bucket_path, "aws_s3_bucket", body)])) == 1
}

test_r4_string_true if {
	body := {"lifecycle": [{"prevent_destroy": "true"}]}
	count(hits("R4", [resource_file(bucket_path, "aws_s3_bucket", body)])) == 1
}

test_r4_variable if {
	body := {"lifecycle": [{"prevent_destroy": "${var.keep}"}]}
	count(hits("R4", [resource_file(bucket_path, "aws_s3_bucket", body)])) == 1
}

test_r4_one_of_two_unprotected if {
	files := [tf(bucket_path, {"resource": {"aws_s3_bucket": {
		"a": [kept],
		"b": [{"bucket": "example"}],
	}}})]
	count(hits("R4", files)) == 1
}

test_r4_protected_in_other_file_does_not_count if {
	files := [
		resource_file(bucket_path, "aws_s3_bucket", {"bucket": "example"}),
		resource_file("modules/state_bucket/extra.tf", "aws_s3_bucket", kept),
	]
	count(hits("R4", files)) == 1
}

# 다른 자원의 prevent_destroy 는 버킷을 지켜 주지 않는다.
test_r4_bucket_absent if {
	files := [resource_file(bucket_path, "aws_s3_bucket_versioning", kept)]
	count(hits("R4", files)) == 1
}

test_r4_absent_message_names_dir if {
	files := [tf("modules/state_bucket/variables.tf", {"variable": {"name": [{}]}})]
	some msg in hits("R4", files)
	startswith(msg, "R4 modules/state_bucket: ")
}

test_r4_skipped_without_files if {
	clean([resource_file(other, "aws_s3_bucket", {"bucket": "example"})])
}

test_r4_other_module_ignored if {
	files := [
		resource_file("modules/other/main.tf", "aws_s3_bucket", {"bucket": "example"}),
		resource_file("modules/state_bucket_extra/main.tf", "aws_s3_bucket", {"bucket": "example"}),
	]
	clean(files)
}

test_r11_all_protected_clean if {
	clean([dev_resources(kept)])
}

test_r11_eip_unprotected if {
	count(hits("R11", [dev_resources({"domain": "vpc"})])) == 1
}

test_r11_eip_absent if {
	files := [tf(dev_path, {"resource": {
		"aws_instance": {"dev": [kept]},
		"aws_security_group": {"dev": [kept]},
	}})]
	msgs := hits("R11", files)
	count(msgs) == 1
	some msg in msgs
	contains(msg, "aws_eip")
}

test_r11_rule_resources_not_targeted if {
	rules := tf("nonprod/dev/rules.tf", {"resource": {
		"aws_vpc_security_group_ingress_rule": {"https": [{"ip_protocol": "tcp"}]},
	}})
	clean([dev_resources(kept), rules])
}

test_r11_skipped_without_files if {
	clean([resource_file(other, "aws_instance", {"ami": "example"})])
}
