# R1 · R2: 비밀 값을 상태 파일에 남기는 자원 · data source 금지.
package main

# R1: 비밀 버전은 자원이든 data source 든 값을 상태에 평문으로 남긴다.
deny contains msg if {
	some decl in declarations
	decl.type == "aws_secretsmanager_secret_version"
	msg := sprintf(
		"R1 %s: %s — aws_secretsmanager_secret_version 금지(비밀 값이 상태에 남음)",
		[decl.path, label(decl)],
	)
}

# R2: KVS 키 자원(aws_cloudfrontkeyvaluestore_key*)도 값을 상태에 남긴다.
deny contains msg if {
	some decl in declarations
	startswith(decl.type, "aws_cloudfrontkeyvaluestore_key")
	msg := sprintf(
		"R2 %s: %s — aws_cloudfrontkeyvaluestore_key* 금지(비밀 값이 상태에 남음)",
		[decl.path, label(decl)],
	)
}
