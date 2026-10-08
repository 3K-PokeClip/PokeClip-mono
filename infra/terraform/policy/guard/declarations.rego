# Terraform 금지 규약(README 「guard 규칙」)의 공용 부분.
#
# 입력은 `conftest test --parser hcl2 --combine` 이 만든 배열이다. 원소마다
# {"path": 트리 루트 기준 상대 경로, "contents": 파일의 hcl2 JSON}.
# hcl2 JSON 에서 자원은 contents.resource[유형][이름] = [본문] 꼴이고, 하위
# 블록(lifecycle · ingress)은 본문 안 배열, dynamic 블록은
# 본문.dynamic[라벨] = [블록] 꼴이다. 식은 평가하지 않고 "${…}" 문자열로
# 남는다. count 도 펼치지 않으므로 count = 0 인 선언도 그대로 보인다.
#
# 위반 메시지는 「R<번호> <상대 경로>: <설명>」 꼴이다(guard.sh · guard_test.sh
# 가 규칙 번호로 판정한다).
package main

# resource · data 선언 하나하나.
declarations contains decl if {
	some file in input
	some kind in {"resource", "data"}
	some type, named in file.contents[kind]
	some name, bodies in named
	some body in bodies
	decl := {
		"path": file.path,
		"kind": kind,
		"type": type,
		"name": name,
		"body": body,
	}
}

# 위반 메시지의 「<kind> "<유형>" "<이름>"」 부분.
label(decl) := sprintf("%s %q %q", [decl.kind, decl.type, decl.name])
