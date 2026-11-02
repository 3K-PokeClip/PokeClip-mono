# R8: 엣지(modules/cdn_media · modules/cdn_static_site · prod/edge)는 관리형
# 캐시 정책 UseOriginCacheControlHeaders 와 그 짝
# UseOriginCacheControlHeaders-QueryStrings 를 쓰지 않는다(설계 3-3 — 쿠키
# 전부를 캐시 키에 넣어 서명 쿠키가 시청자마다 키를 쪼갠다).
#
# 식을 평가하지 않으므로 이렇게 본다.
# ① 대상 경로의 어느 문자열이든(맵 키 포함) 정책 이름
#    UseOriginCacheControlHeaders 가 들어 있으면 위반이다(대소문자 무관 —
#    data 조회 이름 Managed-… · 지역값 · 설명 모두). 짝 정책 이름은 이 이름을
#    품으므로 함께 걸린다.
# ② 어느 문자열이든 두 정책의 id 가 들어 있으면 위반이다(대소문자 무관 —
#    cache_policy_id 리터럴 · data 의 id).
# ③ data "aws_cloudfront_cache_policy" 의 name · id 가 식(보간)이면 위반이다
#    — 무엇을 조회하는지 대조할 수 없다. 관리형 정책은 리터럴로만 조회한다.
# 대상 경로에 파일이 없는 트리(다른 규칙의 fixture)는 위반이 없다.
package main

edge_dirs := {"modules/cdn_media", "modules/cdn_static_site", "prod/edge"}

banned_cache_policy_name := "useorigincachecontrolheaders"

# AWS 문서 using-managed-cache-policies(2026-10-24 확인)의 두 정책 id.
banned_cache_policy_ids := {
	"83da9c7e-98b4-4e11-a168-04f0df8e2c65",
	"4cc15a8a-d715-48a4-82b8-cc0b614638fe",
}

in_edge_dirs(path) if {
	some dir in edge_dirs
	in_dir(path, dir)
}

# 대상 경로의 문자열 — 값과 맵 키.
edge_strings contains item if {
	some file in input
	in_edge_dirs(file.path)
	walk(file.contents, [_, value])
	is_string(value)
	item := {"file": file.path, "value": value}
}

edge_strings contains item if {
	some file in input
	in_edge_dirs(file.path)
	walk(file.contents, [_, value])
	is_object(value)
	some key, _ in value
	item := {"file": file.path, "value": key}
}

deny contains msg if {
	some item in edge_strings
	contains(lower(item.value), banned_cache_policy_name)
	msg := sprintf(
		"R8 %s: 관리형 캐시 정책 UseOriginCacheControlHeaders 계열 이름 참조 금지(쿠키 전부가 캐시 키 — 사용자 정의 정책으로)",
		[item.file],
	)
}

deny contains msg if {
	some item in edge_strings
	some id in banned_cache_policy_ids
	contains(lower(item.value), id)
	msg := sprintf(
		"R8 %s: 관리형 캐시 정책 id %s 참조 금지(UseOriginCacheControlHeaders 계열 — 사용자 정의 정책으로)",
		[item.file, id],
	)
}

deny contains msg if {
	some decl in declarations
	decl.kind == "data"
	decl.type == "aws_cloudfront_cache_policy"
	in_edge_dirs(decl.path)
	some attr in {"name", "id"}
	value := object.get(decl.body, attr, "")
	is_string(value)
	contains(value, "${")
	msg := sprintf(
		"R8 %s: %s 의 %s 가 식 — 조회할 캐시 정책을 대조할 수 없음(리터럴로)",
		[decl.path, label(decl), attr],
	)
}
