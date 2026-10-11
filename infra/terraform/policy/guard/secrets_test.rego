package main

test_r1_resource if {
	files := [resource_file(other, "aws_secretsmanager_secret_version", {"secret_id": "example"})]
	count(hits("R1", files)) == 1
}

test_r1_data_source if {
	files := [tf(other, {"data": {"aws_secretsmanager_secret_version": {"this": [{"secret_id": "example"}]}}})]
	count(hits("R1", files)) == 1
}

# conftest 는 count 를 펼치지 않는다: count = 0 이어도 선언이 있으면 위반이다.
test_r1_count_zero if {
	files := [resource_file(other, "aws_secretsmanager_secret_version", {"count": 0})]
	count(hits("R1", files)) == 1
}

test_r1_message_has_path if {
	files := [resource_file("nested/dir/x.tf", "aws_secretsmanager_secret_version", {})]
	some msg in hits("R1", files)
	startswith(msg, "R1 nested/dir/x.tf: ")
}

test_r1_secret_container_allowed if {
	clean([resource_file(other, "aws_secretsmanager_secret", {"name": "example"})])
}

test_r2_resource if {
	files := [resource_file(other, "aws_cloudfrontkeyvaluestore_key", {"key": "example"})]
	count(hits("R2", files)) == 1
}

test_r2_prefix_type if {
	files := [resource_file(other, "aws_cloudfrontkeyvaluestore_keys_exclusive", {})]
	count(hits("R2", files)) == 1
}

test_r2_key_value_store_allowed if {
	clean([resource_file(other, "aws_cloudfront_key_value_store", {"name": "example"})])
}

test_empty_file_clean if {
	clean([tf(other, {})])
}
