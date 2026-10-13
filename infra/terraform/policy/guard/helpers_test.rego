# 정책 단위 시험의 공용 도우미.
#
# 입력은 `conftest test --parser hcl2 --combine` 이 만드는 꼴 그대로다:
# 파일마다 {"path": 루트 기준 상대 경로, "contents": hcl2 JSON}. contents 의
# 모양은 `conftest parse --parser hcl2 <파일>` 실물에서 옮겼다.
package main

# 파일 하나.
tf(path, contents) := {"path": path, "contents": contents}

# 자원 하나만 든 파일. body 는 블록 본문(속성 · 하위 블록 배열).
resource_file(path, type, body) := tf(path, {"resource": {type: {"this": [body]}}})

# files 를 입력으로 돌렸을 때 rule(예: "R1")로 시작하는 위반 메시지.
hits(rule, files) := {msg |
	some msg in deny with input as files
	startswith(msg, concat("", [rule, " "]))
}

# 위반이 하나도 없는지. 시험 입력은 부분 트리라 fixture 표지로 돌린다 — 운영
# 표지면 공개 존 루트의 부재(R12 ① · ②)가 끼므로(운영 모드는
# public_zone_test.rego 가 따로 본다).
clean(files) if {
	msgs := deny with input as files with data.mode as "fixture"
	count(msgs) == 0
}

# 다른 규칙 시험에서 R4 · R11 이 끼지 않게 쓰는 경로 밖 파일 이름.
other := "main.tf"
