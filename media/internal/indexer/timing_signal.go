package indexer

import "strconv"

// versionText 는 판(timing_version)을 신호 속성 글자로 편다 — nil 은 판 NULL 이라 "NULL" 이다(POK-196 PDT 설계 r7 8절).
// 포인터를 그대로 실으면 로그에 주소가 찍힌다.
func versionText(v *int16) string {
	if v == nil {
		return "NULL"
	}
	return strconv.Itoa(int(*v))
}
