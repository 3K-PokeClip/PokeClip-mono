package credrelay

import (
	"testing"
	"time"
)

// 상수 사이의 관계가 계약이다(설계 2.5 · 계획 E14). 값 하나를 바꿔 관계가 깨지면 이 시험이 그 관계 문구로 실패한다.
func TestConstantRelations(t *testing.T) {
	// consumerExpiryWindow 는 소비자 SDK 캐시가 만료 전에 다시 묻는 창이다(config resolve_credentials.go 의
	// ExpiryWindow 5분). 중계가 정하는 값이 아니라 관계 확인에만 쓴다.
	const consumerExpiryWindow = 5 * time.Minute
	for _, c := range []struct {
		relation string
		ok       bool
	}{
		{"consumerRefreshCap >= 6 * 소비자 캐시 창(5분)", consumerRefreshCap >= 6*consumerExpiryWindow},
		{"periodicReread < consumerRefreshCap", periodicReread < consumerRefreshCap},
		{"serveFloor < refreshWindow (줄 수 있는 동안 다시 읽기 시작)", serveFloor < refreshWindow},
		{"sourceDeadline < writeTimeout (원천이 늦어도 503 · 200 을 끝까지 쓴다)", sourceDeadline < writeTimeout},
		{"sourceDeadline < minSourceInterval (원천 호출이 겹치지 않는다)", sourceDeadline < minSourceInterval},
		{"writeTimeout <= ShutdownGrace (처리 중 요청을 끝까지 기다린다)", writeTimeout <= ShutdownGrace},
	} {
		if !c.ok {
			t.Errorf("상수 관계 깨짐: %s", c.relation)
		}
	}
}
