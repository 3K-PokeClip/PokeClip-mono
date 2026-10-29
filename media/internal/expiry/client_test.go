package expiry

// clip 만료 창구 클라이언트의 전송 설정 시험(POK-196 M6 PR 단위 보안 LOW-1).

import (
	"net/http"
	"testing"
	"time"
)

// 프록시 환경변수(HTTP_PROXY 등)를 타지 않는다 — clip 은 VPC 안 직접 호출이고, 전용 토큰 머리가 프록시를 거치면
// 안 된다. Go 는 루프백 주소에 프록시를 쓰지 않고 환경변수도 프로세스에서 한 번만 읽어 httptest 로는 드러나지
// 않으므로 전송 설정을 직접 본다(keyresolve 의 같은 시험과 같은 꼴). 변이 = 전송을 비움(기본 전송) → FAIL.
func TestClipClientUsesDirectTransport(t *testing.T) {
	c := newClipClient("http://clip.test:8080", NewToken("t"), time.Second, 1<<20)

	tr, ok := c.http.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("만료 클라이언트 전송 = %T, want *http.Transport — nil 이면 프록시 환경변수를 타는 기본 전송이다",
			c.http.Transport)
	}
	if tr.Proxy != nil {
		t.Error("만료 클라이언트 전송에 Proxy 함수가 있다, want nil(직접 연결)")
	}
}
