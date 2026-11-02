package main

# R8 시험 입력. 대상 경로(엣지 모듈 둘 · prod/edge) 안의 파일이다.
media_path := "modules/cdn_media/main.tf"

static_site_path := "modules/cdn_static_site/main.tf"

edge_path := "prod/edge/main.tf"

cache_policy_data(path, name, body) := tf(path, {"data": {"aws_cloudfront_cache_policy": {name: [body]}}})

# 관리형 캐시 정책 id(AWS 문서 using-managed-cache-policies).
origin_headers_id := "83da9c7e-98b4-4e11-a168-04f0df8e2c65"

origin_headers_query_id := "4cc15a8a-d715-48a4-82b8-cc0b614638fe"

caching_optimized_id := "658327ea-f89d-4fab-a63d-7e88639e58f6"

test_r8_data_name_managed_prefix if {
	files := [cache_policy_data(edge_path, "origin", {"name": "Managed-UseOriginCacheControlHeaders"})]
	count(hits("R8", files)) == 1
}

test_r8_data_name_query_strings if {
	files := [cache_policy_data(static_site_path, "origin", {"name": "Managed-UseOriginCacheControlHeaders-QueryStrings"})]
	count(hits("R8", files)) == 1
}

# 접두 없이 · 대소문자를 바꿔 적어도 같은 정책이다.
test_r8_data_name_without_prefix_any_case if {
	files := [cache_policy_data(media_path, "origin", {"name": "useorigincachecontrolheaders"})]
	count(hits("R8", files)) == 1
}

test_r8_data_by_id if {
	files := [cache_policy_data(media_path, "origin", {"id": origin_headers_id})]
	count(hits("R8", files)) == 1
}

test_r8_behavior_cache_policy_id_literal if {
	body := {"default_cache_behavior": [{"cache_policy_id": origin_headers_query_id}]}
	files := [resource_file(media_path, "aws_cloudfront_distribution", body)]
	count(hits("R8", files)) == 1
}

# id 는 대문자로 적어도 같은 정책이다.
test_r8_id_upper_case if {
	body := {"ordered_cache_behavior": [{"cache_policy_id": upper(origin_headers_id)}]}
	files := [resource_file(edge_path, "aws_cloudfront_distribution", body)]
	count(hits("R8", files)) == 1
}

# 지역값에 이름을 두고 참조로 넘겨도 문자열이 대상 경로에 있다.
test_r8_name_in_locals if {
	files := [tf(edge_path, {"locals": [{"origin_policy": "Managed-UseOriginCacheControlHeaders"}]})]
	count(hits("R8", files)) == 1
}

test_r8_id_as_map_key if {
	files := [tf(edge_path, {"locals": [{"policies": {origin_headers_id: "origin"}}]})]
	count(hits("R8", files)) == 1
}

# 이름 · id 가 식이면 대조할 수 없다 — 관리형 정책은 리터럴로만 조회한다.
test_r8_data_name_expression if {
	files := [cache_policy_data(edge_path, "chosen", {"name": "${var.cache_policy_name}"})]
	count(hits("R8", files)) == 1
}

test_r8_data_id_expression if {
	files := [cache_policy_data(media_path, "chosen", {"id": "${local.policy_id}"})]
	count(hits("R8", files)) == 1
}

test_r8_message_has_path if {
	files := [cache_policy_data(edge_path, "origin", {"name": "Managed-UseOriginCacheControlHeaders"})]
	some msg in hits("R8", files)
	startswith(msg, "R8 prod/edge/main.tf: ")
}

# 하위 디렉터리(시험 파일 등)도 대상 경로다.
test_r8_nested_path if {
	files := [tf("modules/cdn_media/tests/main.tf", {"locals": [{"id": origin_headers_id}]})]
	count(hits("R8", files)) == 1
}

test_r8_other_managed_policy_clean if {
	clean([cache_policy_data(static_site_path, "optimized", {"name": "Managed-CachingOptimized"})])
}

test_r8_other_managed_id_clean if {
	body := {"default_cache_behavior": [{"cache_policy_id": caching_optimized_id}]}
	clean([resource_file(static_site_path, "aws_cloudfront_distribution", body)])
}

test_r8_custom_policy_reference_clean if {
	body := {"default_cache_behavior": [{"cache_policy_id": "${aws_cloudfront_cache_policy.live.id}"}]}
	clean([resource_file(media_path, "aws_cloudfront_distribution", body)])
}

# 대상 경로 밖(엣지가 아닌 루트 · 모듈)은 보지 않는다.
test_r8_outside_target_clean if {
	files := [
		cache_policy_data("prod/runtime/main.tf", "origin", {"name": "Managed-UseOriginCacheControlHeaders"}),
		cache_policy_data("modules/cdn_media_extra/main.tf", "origin", {"id": origin_headers_id}),
	]
	clean(files)
}
