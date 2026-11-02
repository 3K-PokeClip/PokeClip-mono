# R6: 비밀 그릇 모듈(modules/secret_shells)은 그릇만 만든다(설계 3-3 —
# 버전 · 리소스 정책 · 읽기 역할 입력 없음).
#
# 리소스 정책 자원 aws_secretsmanager_secret_policy 와 그릇 자원의 인라인
# policy 인자(kty 61차)를 막는다 — 읽기 권한은 읽는 쪽 루트가 자기 역할
# 정책으로 붙인다. 입력은 names 하나뿐이라 그 밖의 variable(읽기 역할 등)도
# 막는다. 버전 자원은 R1 이 전체에서 막는다. 대상 경로에 파일이 없는
# 트리(다른 규칙의 fixture)는 위반이 없다(R4 와 같이 건너뜀).
package main

shells_dir := "modules/secret_shells"

allowed_shells_variables := {"names"}

deny contains msg if {
	some decl in declarations
	in_dir(decl.path, shells_dir)
	decl.type == "aws_secretsmanager_secret_policy"
	msg := sprintf(
		"R6 %s: %s — 비밀 리소스 정책 금지(읽기 권한은 읽는 쪽 역할 정책)",
		[decl.path, label(decl)],
	)
}

deny contains msg if {
	some decl in declarations
	in_dir(decl.path, shells_dir)
	decl.kind == "resource"
	decl.type == "aws_secretsmanager_secret"
	"policy" in object.keys(decl.body)
	msg := sprintf(
		"R6 %s: %s 의 policy 인자 금지(인라인 리소스 정책 — 읽기 권한은 읽는 쪽 역할 정책)",
		[decl.path, label(decl)],
	)
}

deny contains msg if {
	some file in input
	in_dir(file.path, shells_dir)
	some name, _ in file.contents.variable
	not name in allowed_shells_variables
	msg := sprintf(
		"R6 %s: variable %q — 입력은 names 만(읽기 역할 등 다른 입력 금지)",
		[file.path, name],
	)
}
