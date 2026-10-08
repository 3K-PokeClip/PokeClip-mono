# R3: aws_security_group 안의 인라인 규칙 금지(규칙은 별도 자원으로).
package main

inline_rule_kinds := {"ingress", "egress"}

# 본문 안 인라인 규칙의 이름. 블록 `ingress { }` 와 속성 `ingress = […]` 는
# hcl2 JSON 에서 같은 키라 함께 잡힌다(값이 [] · null 이어도 키가 있으면 위반).
inline_rules(body) := plain | dynamic if {
	plain := {kind |
		some kind in inline_rule_kinds
		kind in object.keys(body)
	}
	dynamic := {sprintf("dynamic %q", [kind]) |
		some kind in inline_rule_kinds
		kind in object.keys(object.get(body, "dynamic", {}))
	}
}

deny contains msg if {
	some decl in declarations
	decl.kind == "resource"
	decl.type == "aws_security_group"
	some found in inline_rules(decl.body)
	msg := sprintf(
		"R3 %s: %s 안 인라인 %s 금지(aws_vpc_security_group_*_rule 자원으로)",
		[decl.path, label(decl), found],
	)
}
