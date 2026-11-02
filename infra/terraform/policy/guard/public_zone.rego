# R12: 공개 존 pokeclip.com 의 영구 소유 루트 nonprod/dns-legacy 를 지킨다
# (설계 3-1 「공개 존을 지우는 경로와 막는 수단」).
#
# 운영 트리에서는 ① 루트 바로 아래 .tf ② 주소가 정확히
# aws_route53_zone.legacy 인 선언 ③ 그 루트의 모든 aws_route53_zone 에 리터럴
# prevent_destroy = true 를 요구한다. R4 · R11 과 달리 부재를 건너뛰지 않는다 —
# 루트 파일을 지우거나 옮기면 prevent_destroy 도 함께 사라져 아무것도 막지
# 못하기 때문이다. fixture 트리만 ① · ② 의 부재를 건너뛰고, ③ 은 늘 본다.
#
# 두 트리를 가르는 표지는 guard.sh 가 --data 로 넘기는 data.mode 다. 정확히
# "fixture" 일 때만 fixture 트리이고, 표지가 없거나 모르는 값이면 운영 트리로
# 본다(fail-closed).
package main

public_zone_root := "nonprod/dns-legacy"

fixture_tree if data.mode == "fixture"

# Terraform 이 루트 구성으로 읽는 파일 — 루트 바로 아래(하위 디렉터리는 아님).
in_root(path, root) if {
	startswith(path, concat("", [root, "/"]))
	not contains(substring(path, count(root) + 1, -1), "/")
}

public_zone_root_files := {file.path |
	some file in input
	in_root(file.path, public_zone_root)
}

public_zones := {decl |
	some decl in declarations
	decl.kind == "resource"
	decl.type == "aws_route53_zone"
	in_root(decl.path, public_zone_root)
}

legacy_zone_declared if {
	some decl in public_zones
	decl.name == "legacy"
}

# ①
deny contains msg if {
	not fixture_tree
	count(public_zone_root_files) == 0
	msg := sprintf(
		"R12 %s: .tf 없음 — 운영 트리는 공개 존 소유 루트를 요구(건너뛰지 않음)",
		[public_zone_root],
	)
}

# ②
deny contains msg if {
	not fixture_tree
	count(public_zone_root_files) > 0
	not legacy_zone_declared
	msg := sprintf(
		"R12 %s: resource %q %q 선언 없음 — 공개 존 주소는 고정(바꾸면 R12 도 같은 PR 에서)",
		[public_zone_root, "aws_route53_zone", "legacy"],
	)
}

# ③
deny contains msg if {
	some decl in public_zones
	not prevents_destroy(decl.body)
	msg := sprintf(
		"R12 %s: %s 의 lifecycle 에 prevent_destroy = true(리터럴) 없음",
		[decl.path, label(decl)],
	)
}
