package config

import (
	"errors"
	"net/netip"
	"path/filepath"
	"strings"
	"unicode"
)

// mtxAPIAuth 는 관측 폴러의 Control API 자격 env 두 개를 문자열로 검사한 결과다(POK-373).
// 둘 다 빈 값이면 무인증이다(dev compose — MediaMTX 의 `user: any` 항목으로 붙는다).
type mtxAPIAuth struct {
	user     string // MTX_API_USER — Basic 자격의 사용자 이름
	passFile string // MTX_API_PASS_FILE — 비밀번호 파일의 절대 경로. 여기서는 열지 않는다
}

// loadMTXAPIAuth 는 MTX_API_USER · MTX_API_PASS_FILE 을 읽어 문자열 규칙만 본다. apiHost 는
// validateMTXAPIURL 이 돌려준 MTX_API_URL 의 호스트다(빈 값 = 관측 끔).
//
// **비밀번호 파일은 열지 않는다** — 설정은 값만 읽고 파일 손잡이는 main 이 만든다(SEGMENT_ROOT 와 같은
// 층 규칙). 읽기 · 권한 · 내용 검사는 main 의 readSecretFile 이 지고, 그 실패도 기동 실패다.
//
// 관측이 꺼져 있어도(apiHost 빈 값) 짝 · 이름 · 경로 문자열은 본다 — OBS_* 와 같은 「꺼져도 값은 본다」
// 규칙이다(오타를 켜는 날에야 알게 되지 않게). 파일 자체는 main 도 URL 이 비면 읽지 않는다.
//
// 사이드카는 MTX_API_PASS(응답기의 env 값 방식)를 읽지 않는다 — 비밀번호는 파일로만 받는다(env 값은
// docker inspect 에 남는다 — ADR-018). 그 값만 있으면 무인증으로 기동한다(kty 2026-10-19).
//
// 에러에는 env 이름과 무엇이 틀렸는지만 싣는다 — 사용자 이름 · 경로 · URL 의 값은 싣지 않는다. 값을
// 다른 자리에 잘못 넣는 실수(MTX_API_PASS_FILE=<비밀번호>)가 바로 이 검사들에 걸리고, 그 에러는 stderr 로
// 나가기 때문이다.
func loadMTXAPIAuth(env func(string) string, apiHost string) (mtxAPIAuth, error) {
	a := mtxAPIAuth{
		user:     strings.TrimSpace(env("MTX_API_USER")),
		passFile: strings.TrimSpace(env("MTX_API_PASS_FILE")),
	}
	if a.user == "" && a.passFile == "" {
		return mtxAPIAuth{}, nil
	}
	// 짝 규칙 — 하나만이면 조용히 무인증으로 붙거나(MediaMTX 에 무인증 api 항목이 없을 때만 401 로 뒤늦게
	// 드러나고, 있으면 아무 신호도 없다) 비밀번호 파일이 쓰이지 않는다. 기동 때 끊는다.
	if a.user == "" || a.passFile == "" {
		return mtxAPIAuth{}, errors.New("MTX_API_USER 와 MTX_API_PASS_FILE 은 함께 넣거나 함께 비워야 한다 — 둘 다 비면 무인증이다")
	}
	// Basic 은 첫 ':' 에서 사용자와 비밀번호를 가른다(RFC 7617 2절) — 이름에 ':' 가 있으면 MediaMTX 에는
	// 다른 사용자로 보인다. 가운데 제어 문자(CRLF .env 의 '\r' 은 TrimSpace 가 끝에서만 걷는다)도 거부한다.
	if strings.Contains(a.user, ":") {
		return mtxAPIAuth{}, errors.New("MTX_API_USER 에 ':' 를 넣을 수 없다 — Basic 자격은 첫 ':' 에서 사용자와 비밀번호를 가른다")
	}
	if strings.IndexFunc(a.user, unicode.IsControl) >= 0 {
		return mtxAPIAuth{}, errors.New("MTX_API_USER 에 제어 문자(탭 · 줄바꿈 등)를 넣을 수 없다")
	}
	// 상대 경로는 작업 디렉터리에 따라 다른 파일을 가리킨다 — 컨테이너 안 절대 경로만 받는다.
	if !filepath.IsAbs(a.passFile) {
		return mtxAPIAuth{}, errors.New("MTX_API_PASS_FILE 은 컨테이너 안의 절대 경로여야 한다 — 상대 경로는 작업 디렉터리에 따라 다른 파일을 가리킨다")
	}
	if apiHost == "" {
		return a, nil
	}
	// 자격을 실으면 Basic 자격이 DNS 가 가리키는 곳으로 가지 않게 IP 리터럴 호스트만 받는다(응답기
	// Control API 클라이언트 mtxctl 과 같은 규칙).
	if _, err := netip.ParseAddr(apiHost); err != nil {
		return mtxAPIAuth{}, errors.New("자격(MTX_API_USER · MTX_API_PASS_FILE)을 넣으면 MTX_API_URL 의 호스트는 IP 리터럴이어야 한다 — Basic 자격이 DNS 가 가리키는 곳으로 가지 않게 한다(예: http://127.0.0.1:9997)")
	}
	return a, nil
}
