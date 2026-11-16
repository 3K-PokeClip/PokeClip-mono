package main

import (
	"fmt"
	"net/netip"
	"path/filepath"
	"strings"
)

// serve 의 환경 변수다. compose 렌더(infra/media-host/compose.yml.tmpl)와 같은 이름이어야 한다 — 렌더 시험이
// 정확값으로 단언한다(계획 E21: 디렉터리 변수 이름에 TOKEN 을 넣지 않는다).
const (
	envListen    = "CRED_RELAY_LISTEN"     // 듣는 IP:포트 — 켬 상자는 169.254.170.2:9911
	envCallerDir = "CRED_RELAY_CALLER_DIR" // 호출자 이름마다 토큰 파일 하나가 든 디렉터리(절대 경로)
	envCallers   = "CRED_RELAY_CALLERS"    // 호출자 이름 목록(쉼표로 가름) — 이름 꼴 · 겹침은 credrelay 가 본다
)

// config 는 serve 의 설정이다. 비밀이 없다 — 토큰은 callerDir 안 파일에만 있다.
type config struct {
	listen    netip.AddrPort
	callerDir string
	callers   []string
}

// configError 는 설정 거부다. 값은 싣지 않고 변수 이름(env)과 까닭만 싣는다 — cred_relay_failed 줄의 env 가 된다.
type configError struct {
	env, why string
}

func (e *configError) Error() string {
	return fmt.Sprintf("%s: %s", e.env, e.why)
}

// loadConfig 는 serve 의 환경 변수를 읽어 꼴을 본다. 듣는 주소는 IPv4 링크 로컬(169.254.0.0/16)만 받는다 — 중계는
// 호스트 망이라, 모든 주소 · 상자의 사설 주소로 들으면 자격 창구가 상자 밖 망에도 열린다. 켬 상자의 주소
// 169.254.170.2 는 시작 스크립트가 더미 링크에 붙인다. 호출자 이름의 꼴 · 겹침은 여기서 보지 않는다 —
// credrelay.LoadTokens 가 파일을 보기 전에 본다(ErrCallers).
func loadConfig(getenv func(string) string) (config, error) {
	listen, err := listenAddr(getenv(envListen))
	if err != nil {
		return config{}, err
	}
	if !listen.Addr().Is4() || !listen.Addr().IsLinkLocalUnicast() {
		return config{}, &configError{env: envListen, why: "IPv4 링크 로컬(169.254.0.0/16) 주소가 아니다"}
	}
	dir := getenv(envCallerDir)
	if dir == "" || !filepath.IsAbs(dir) || filepath.Clean(dir) != dir {
		return config{}, &configError{env: envCallerDir, why: "정리된 절대 경로가 아니다"}
	}
	callers := getenv(envCallers)
	if callers == "" {
		return config{}, &configError{env: envCallers, why: "비었다"}
	}
	return config{listen: listen, callerDir: dir, callers: strings.Split(callers, ",")}, nil
}

// listenAddr 는 v 를 IP:포트(이름 풀이 없음 · 포트 0 아님)로 읽는다. serve 와 healthcheck 가 함께 쓴다.
func listenAddr(v string) (netip.AddrPort, error) {
	ap, err := netip.ParseAddrPort(v)
	if err != nil || ap.Port() == 0 {
		return netip.AddrPort{}, &configError{env: envListen, why: "IP:포트(포트 0 아님) 꼴이 아니다"}
	}
	return ap, nil
}
