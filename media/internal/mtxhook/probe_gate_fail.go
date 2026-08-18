package mtxhook

// 폐기용 — develop 브랜치 보호의 차단 경로 실증 전용. 머지 금지.
// gofmt는 통과하고 컴파일에서 깨진다(int에 string 대입).
func probeGateFail() int {
	var n int = "media-gate 차단 경로 실증"
	return n
}
